<script setup lang="tsx">
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NEllipsis, NModal, NPopconfirm, NProgress, NTag, NUpload } from 'naive-ui';
import { uploadAccept } from '@/constants/common';
import { fakePaginationRequest } from '@/service/request';
import { UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';

const appStore = useAppStore();

// File preview state
const previewVisible = ref(false);
const previewFileName = ref('');

function apiFn() {
  return fakePaginationRequest<Api.KnowledgeBase.List>({ url: '/documents/uploads' });
}

function renderIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return <SvgIcon localIcon={ext} class="mx-4 text-12" />;
    return <SvgIcon localIcon="dflt" class="mx-4 text-12" />;
  }
  return null;
}

// Open file preview
function handleFilePreview(fileName: string) {
  previewFileName.value = fileName;
  previewVisible.value = true;
}

// Close file preview
function closeFilePreview() {
  previewVisible.value = false;
  previewFileName.value = '';
}

const { columns, columnChecks, data, getData, loading } = useTable({
  apiFn,
  immediate: false,
  columns: () => [
    {
      key: 'fileName',
      title: 'File Name',
      minWidth: 400,
      render: row => (
        <div class="flex items-center">
          {renderIcon(row.fileName)}
          <NEllipsis lineClamp={2} tooltip>
            <span
              class="cursor-pointer hover:text-primary transition-colors"
              onClick={() => handleFilePreview(row.fileName)}
            >
              {row.fileName}
            </span>
          </NEllipsis>
        </div>
      )
    },
    {
      key: 'totalSize',
      title: 'File Size',
      width: 100,
      render: row => fileSize(row.totalSize)
    },
    {
      key: 'status',
      title: 'Upload Status',
      width: 100,
      render: row => renderStatus(row.status, row.progress)
    },
    {
      key: 'orgTagName',
      title: 'Organization Tag',
      width: 150,
      ellipsis: { tooltip: true, lineClamp: 2 }
    },
    {
      key: 'isPublic',
      title: 'Visibility',
      width: 100,
      render: row => (row.public || row.isPublic ? <NTag type="success">Public</NTag> : <NTag type="warning">Private</NTag>)
    },
    {
      key: 'createdAt',
      title: 'Uploaded At',
      width: 100,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD')
    },
    {
      key: 'operate',
      title: 'Actions',
      width: 180,
      render: row => (
        <div class="flex gap-4">
          {renderResumeUploadButton(row)}
          <NButton
            type="primary"
            ghost
            size="small"
            onClick={() => handleFilePreview(row.fileName)}
          >
            Preview
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.fileMd5)}>
            {{
              default: () => 'Delete this file?',
              trigger: () => (
                <NButton type="error" ghost size="small">
                  Delete
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
onMounted(async () => {
  await getList();
});

/** Fetch the latest list and sync upload task state. */
async function getList() {
  // Fetch the latest data
  await getData();

  if (data.value.length === 0) {
    tasks.value = [];
    return;
  }

  // Sync each item into the task list
  data.value.forEach(item => {
    // Check whether the item is completed
    if (item.status === UploadStatus.Completed) {
      // Find the matching file MD5 in the task list
      const index = tasks.value.findIndex(task => task.fileMd5 === item.fileMd5);
      // Update the matched task state
      if (index !== -1) {
        tasks.value[index].status = UploadStatus.Completed;
      } else {
        // Add missing completed items to the task list
        tasks.value.push(item);
      }
    } else if (!tasks.value.some(task => task.fileMd5 === item.fileMd5)) {
      // Mark unfinished items that are not already tracked as interrupted
      item.status = UploadStatus.Break;
      tasks.value.push(item);
    }
  });
}

async function handleDelete(fileMd5: string) {
  const index = tasks.value.findIndex(task => task.fileMd5 === fileMd5);

  if (index !== -1) {
    tasks.value[index].requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  // If no chunk has been uploaded, remove the local task directly
  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/documents/${fileMd5}`, method: 'DELETE' });
  if (!error) {
    tasks.value.splice(index, 1);
    window.$message?.success('Deleted successfully');
    await getData();
  }
}

// #region File upload
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

// #region Knowledge base search
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

// Render upload status
function renderStatus(status: UploadStatus, percentage: number) {
  if (status === UploadStatus.Completed) return <NTag type="success">Completed</NTag>;
  else if (status === UploadStatus.Break) return <NTag type="error">Interrupted</NTag>;
  return <NProgress percentage={percentage} processing />;
}

// #region Resume upload
function renderResumeUploadButton(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Break) {
    if (row.file)
      return (
        <NButton type="primary" size="small" ghost onClick={() => resumeUpload(row)}>
          Resume
        </NButton>
      );
    return (
      <NUpload
        show-file-list={false}
        default-upload={false}
        accept={uploadAccept}
        onBeforeUpload={options => onBeforeUpload(options, row)}
        class="w-fit"
      >
        <NButton type="primary" size="small" ghost>
          Resume
        </NButton>
      </NUpload>
    );
  }
  return null;
}

// Resume directly when the task has the original file
function resumeUpload(row: Api.KnowledgeBase.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(
  options: { file: UploadFileInfo; fileList: UploadFileInfo[] },
  row: Api.KnowledgeBase.UploadTask
) {
  const md5 = await calculateMD5(options.file.file!);
  if (md5 !== row.fileMd5) {
    window.$message?.error('The selected file does not match the original upload');
    return false;
  }
  loading.value = true;
  const { error, data: progress } = await request<Api.KnowledgeBase.Progress>({
    url: '/upload/status',
    params: { file_md5: row.fileMd5 }
  });
  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }
  loading.value = false;
  return false;
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="Files" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleUpload" @refresh="getList">
          <template #prefix>
            <NButton size="small" ghost type="primary" @click="handleSearch">
              <template #icon>
                <icon-ic-round-search class="text-icon" />
              </template>
              Search Knowledge Base
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>
      <NDataTable
        striped
        :columns="columns"
        :data="tasks"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="false"
        class="sm:h-full"
      />
    </NCard>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />
    
    <!-- File preview modal -->
    <NModal v-model:show="previewVisible" preset="card" title="File Preview" style="width: 80%; max-width: 1000px;">
      <FilePreview
        :file-name="previewFileName"
        :visible="previewVisible"
        @close="closeFilePreview"
      />
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.file-list-container {
  transition: width 0.3s ease;
}

:deep() {
  .n-progress-icon.n-progress-icon--as-text {
    white-space: nowrap;
  }
}
</style>
