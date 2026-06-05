<template>
  <div class="file-preview-container">
    <div class="preview-header">
      <div class="flex items-center gap-2">
        <SvgIcon :local-icon="getFileIcon(fileName)" class="text-16" />
        <span class="font-medium truncate max-w-200px" :title="fileName">{{ fileName }}</span>
      </div>
      <div class="flex items-center gap-2">
        <NButton size="small" @click="downloadFile" :loading="downloading">
          <template #icon>
            <icon-mdi-download />
          </template>
          Download
        </NButton>
        <NButton size="small" @click="closePreview">
          <template #icon>
            <icon-mdi-close />
          </template>
        </NButton>
      </div>
    </div>

    <div class="preview-content">
      <template v-if="loading">
        <div class="flex items-center justify-center h-full">
          <NSpin size="large" />
        </div>
      </template>
      <template v-else-if="error">
        <div class="flex flex-col items-center justify-center h-full text-gray-500">
          <icon-mdi-alert-circle class="text-48 mb-4" />
          <p>{{ error }}</p>
        </div>
      </template>
      <template v-else>
        <!-- PDF: iframe with presigned URL -->
        <iframe
          v-if="fileType === 'pdf' && presignedUrl"
          :src="presignedUrl"
          class="w-full h-full border-0"
          title="PDF Preview"
        />
        <!-- Images -->
        <div v-else-if="fileType === 'image' && presignedUrl" class="flex items-center justify-center h-full overflow-auto p-4">
          <img :src="presignedUrl" :alt="fileName" class="max-w-full max-h-full object-contain" />
        </div>
        <!-- Text / Markdown -->
        <div v-else-if="textContent" class="content-wrapper">
          <pre class="preview-text">{{ textContent }}</pre>
        </div>
        <!-- Unsupported format -->
        <div v-else class="flex flex-col items-center justify-center h-full text-gray-500 gap-4">
          <icon-mdi-file-question class="text-48" />
          <p>Preview not available for this file type.</p>
          <NButton type="primary" @click="downloadFile" :loading="downloading">Download to view</NButton>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';
import { getServiceBaseURL } from '@/utils/service';
import { localStg } from '@/utils/storage';
import SvgIcon from '@/components/custom/svg-icon.vue';

const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);

interface Props {
  fileName: string;
  visible: boolean;
}

interface Emits {
  (e: 'close'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const loading = ref(false);
const downloading = ref(false);
const textContent = ref('');
const presignedUrl = ref('');
const error = ref('');

const fileType = computed(() => {
  const ext = getFileExt(props.fileName)?.toLowerCase();
  if (ext === 'pdf') return 'pdf';
  if (ext && ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg'].includes(ext)) return 'image';
  if (ext && ['txt', 'md', 'csv', 'json', 'xml', 'yaml', 'yml', 'log'].includes(ext)) return 'text';
  return 'other';
});

function getFileIcon(name: string) {
  const ext = getFileExt(name);
  if (ext) {
    const supported = ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png', 'gif'];
    return supported.includes(ext.toLowerCase()) ? ext : 'dflt';
  }
  return 'dflt';
}

watch(() => [props.fileName, props.visible] as const, ([name, visible]) => {
  if (name && visible) loadPreview();
}, { immediate: true });

async function loadPreview() {
  if (!props.fileName) return;
  loading.value = true;
  error.value = '';
  textContent.value = '';
  presignedUrl.value = '';

  try {
    const token = localStg.get('token');

    if (fileType.value === 'pdf' || fileType.value === 'image') {
      // Backend stream endpoint: sets Content-Type + Content-Disposition: inline
      const params = new URLSearchParams({ fileName: props.fileName });
      if (token) params.set('token', token);
      params.set('_t', Date.now().toString());
      presignedUrl.value = `${baseURL}/documents/stream?${params.toString()}`;
    } else {
      // Get text content for readable files
      const { error: reqErr, data } = await request<{ fileName: string; content: string }>({
        url: '/documents/preview',
        params: { fileName: props.fileName, token: token || undefined }
      });
      if (reqErr || !data) {
        error.value = `Preview failed: ${reqErr?.message ?? 'Unknown error'}`;
      } else {
        textContent.value = data.content;
      }
    }
  } catch (err: any) {
    error.value = `Preview failed: ${err.message ?? 'Network error'}`;
  } finally {
    loading.value = false;
  }
}

async function downloadFile() {
  if (!props.fileName) return;
  downloading.value = true;
  try {
    const token = localStg.get('token');
    const { error: reqErr, data } = await request<{ fileName: string; downloadUrl: string }>({
      url: '/documents/download',
      params: { fileName: props.fileName, token: token || undefined }
    });
    if (reqErr || !data) {
      window.$message?.error(`Download failed: ${reqErr?.message ?? 'Unknown error'}`);
    } else {
      const link = document.createElement('a');
      link.href = data.downloadUrl;
      link.download = data.fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.$message?.success('Download started');
    }
  } catch (err: any) {
    window.$message?.error(`Download failed: ${err.message ?? 'Network error'}`);
  } finally {
    downloading.value = false;
  }
}

function closePreview() {
  emit('close');
}
</script>

<style scoped lang="scss">
.file-preview-container {
  @apply h-full flex flex-col bg-white;

  .preview-header {
    @apply flex items-center justify-between p-4 border-b border-gray-200 bg-gray-50 shrink-0;
  }

  .preview-content {
    @apply flex-1 overflow-hidden;

    iframe {
      display: block;
    }

    .content-wrapper {
      @apply h-full overflow-auto p-4;
    }

    .preview-text {
      @apply text-sm font-mono whitespace-pre-wrap break-words;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      line-height: 1.5;
      margin: 0;
    }
  }
}
</style>
