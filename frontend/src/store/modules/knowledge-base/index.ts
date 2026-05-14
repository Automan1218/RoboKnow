import { REQUEST_ID_KEY } from '~/packages/axios/src';
import { nanoid } from '~/packages/utils/src';

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const tasks = ref<Api.KnowledgeBase.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());

  async function uploadChunk(task: Api.KnowledgeBase.UploadTask): Promise<boolean> {
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    const chunkStart = task.chunkIndex * chunkSize;
    const chunkEnd = Math.min(chunkStart + chunkSize, task.totalSize);
    const chunk = task.file.slice(chunkStart, chunkEnd);

    task.chunk = chunk;
    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);
    const { error, data } = await request<Api.KnowledgeBase.Progress>({
      url: '/upload/chunk',
      method: 'POST',
      data: {
        file: task.chunk,
        fileMd5: task.fileMd5,
        chunkIndex: task.chunkIndex,
        totalSize: task.totalSize,
        fileName: task.fileName,
        orgTag: task.orgTag,
        isPublic: task.isPublic ?? false
      },
      headers: {
        'Content-Type': 'multipart/form-data',
        [REQUEST_ID_KEY]: requestId
      },
      timeout: 10 * 60 * 1000
    });

    task.requestIds = task.requestIds.filter(id => id !== requestId);

    if (error) return false;

    // Update task state
    const updatedTask = tasks.value.find(t => t.fileMd5 === task.fileMd5)!;
    updatedTask.uploadedChunks = data.uploaded;
    updatedTask.progress = Number.parseFloat(data.progress.toFixed(2));

    if (data.uploaded.length === totalChunks) {
      const success = await mergeFile(task);
      if (!success) return false;
    }
    return true;
  }

  async function mergeFile(task: Api.KnowledgeBase.UploadTask) {
    try {
      const { error } = await request({
        url: '/upload/merge',
        method: 'POST',
        data: { fileMd5: task.fileMd5, fileName: task.fileName }
      });
      if (error) return false;

      // Mark task as completed
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      tasks.value[index].status = UploadStatus.Completed;
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Add an upload request to the queue.
   *
   * This handles upload queuing and initialization. It reuses an existing matching task when possible,
   * otherwise creates a new task and starts the upload flow.
   *
   * @param form Upload form data, including files and visibility settings.
   */
  async function enqueueUpload(form: Api.KnowledgeBase.Form) {
    // Use the first file in the list
    const file = form.fileList![0].file!;
    // Calculate the MD5 value as the unique file identifier
    const md5 = await calculateMD5(file);

    // Check whether the same file already exists
    const existingTask = tasks.value.find(t => t.fileMd5 === md5);
    if (existingTask) {
      // Reuse the existing matching task
      if (existingTask.status === UploadStatus.Completed) {
        window.$message?.error('File already exists');
        return;
      } else if (existingTask.status === UploadStatus.Pending || existingTask.status === UploadStatus.Uploading) {
        window.$message?.error('File is already uploading');
        return;
      } else if (existingTask.status === UploadStatus.Break) {
        existingTask.status = UploadStatus.Pending;
        startUpload();
        return;
      }
    }

    // Create a new upload task
    const newTask: Api.KnowledgeBase.UploadTask = {
      file,
      chunk: null,
      chunkIndex: 0,
      fileMd5: md5,
      fileName: file.name,
      totalSize: file.size,
      public: form.isPublic,
      isPublic: form.isPublic,
      uploadedChunks: [],
      progress: 0,
      status: UploadStatus.Pending,
      orgTag: form.orgTag
    };

    newTask.orgTagName = form.orgTagName ?? null;

    // Add the new task to the queue
    tasks.value.push(newTask);
    // Start the upload flow
    startUpload();
    // The new task is now queued
  }

  /** Start upload tasks from the pending queue and limit concurrency. */
  async function startUpload() {
    // Limit concurrent uploads
    if (activeUploads.value.size >= 3) return;
    // Get pending files
    const pendingTasks = tasks.value.filter(
      t => t.status === UploadStatus.Pending && !activeUploads.value.has(t.fileMd5)
    );

    // Return when nothing is pending
    if (pendingTasks.length === 0) return;

    // Pick the first pending file
    const task = pendingTasks[0];
    task.status = UploadStatus.Uploading;
    activeUploads.value.add(task.fileMd5);

    // Calculate total chunk count
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    try {
      if (task.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('File merge failed');
      }
      // const promises = [];
      // Iterate through all chunks
      for (let i = 0; i < totalChunks; i += 1) {
        // Upload chunks that have not been uploaded
        if (!task.uploadedChunks.includes(i)) {
          task.chunkIndex = i;
          // promises.push(uploadChunk(task))
          // eslint-disable-next-line no-await-in-loop
          const success = await uploadChunk(task);
          if (!success) throw new Error('Chunk upload failed');
        }
      }
      // await Promise.all(promises)
    } catch (e) {
      console.error('%c [ 👉 upload error 👈 ]-168', 'font-size:16px; background:#94cc97; color:#d8ffdb;', e);
      // Mark the task as interrupted when upload fails
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      tasks.value[index].status = UploadStatus.Break;
    } finally {
      // Always remove the task from the active queue
      activeUploads.value.delete(task.fileMd5);
      // Continue with the next task
      startUpload();
    }
  }

  return {
    tasks,
    activeUploads,
    enqueueUpload,
    startUpload
  };
});
