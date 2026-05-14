<template>
  <div class="file-preview-container">
    <!-- Preview header -->
    <div class="preview-header">
      <div class="flex items-center gap-2">
        <SvgIcon :local-icon="getFileIcon(fileName)" class="text-16" />
        <span class="font-medium">{{ fileName }}</span>
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
    
    <!-- Preview content -->
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
        <div class="content-wrapper">
          <pre class="preview-text">{{ content }}</pre>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { NButton, NSpin } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { request } from '@/service/request';
import { getFileExt } from '@/utils/common';

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
const content = ref('');
const error = ref('');

// Get file icon
function getFileIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    const supportedIcons = ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png', 'gif'];
    return supportedIcons.includes(ext.toLowerCase()) ? ext : 'dflt';
  }
  return 'dflt';
}

// Reload preview content when the file name changes
watch(() => props.fileName, async (newFileName) => {
  if (newFileName && props.visible) {
    await loadPreviewContent();
  }
}, { immediate: true });

// Watch visibility changes
watch(() => props.visible, async (visible) => {
  if (visible && props.fileName) {
    await loadPreviewContent();
  }
});

// Load preview content
async function loadPreviewContent() {
  if (!props.fileName) return;
  
  loading.value = true;
  error.value = '';
  content.value = '';
  
  try {
    const token = localStorage.getItem('token');
    const { error: requestError, data } = await request<{
      fileName: string;
      content: string;
      fileSize: number;
    }>({
      url: '/documents/preview',
      params: {
        fileName: props.fileName,
        token: token || undefined
      }
    });
    
    if (requestError) {
      error.value = `Preview failed: ${requestError.message || 'Unknown error'}`;
    } else if (data) {
      content.value = data.content;
    }
  } catch (err: any) {
    error.value = `Preview failed: ${err.message || 'Network error'}`;
  } finally {
    loading.value = false;
  }
}

// Download file
async function downloadFile() {
  if (!props.fileName) return;
  
  downloading.value = true;
  
  try {
    const token = localStorage.getItem('token');
    const { error: requestError, data } = await request<{
      fileName: string;
      downloadUrl: string;
      fileSize: number;
    }>({
      url: '/documents/download',
      params: {
        fileName: props.fileName,
        token: token || undefined
      }
    });
    
    if (requestError) {
      window.$message?.error(`Download failed: ${requestError.message || 'Unknown error'}`);
    } else if (data) {
      // Download the file with the presigned URL
      const link = document.createElement('a');
      link.href = data.downloadUrl;
      link.download = data.fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.$message?.success('File download started');
    }
  } catch (err: any) {
    window.$message?.error(`Download failed: ${err.message || 'Network error'}`);
  } finally {
    downloading.value = false;
  }
}

// Close preview
function closePreview() {
  emit('close');
}
</script>

<style scoped lang="scss">
.file-preview-container {
  @apply h-full flex flex-col bg-white border-l border-gray-200;
  
  .preview-header {
    @apply flex items-center justify-between p-4 border-b border-gray-200 bg-gray-50;
  }
  
  .preview-content {
    @apply flex-1 overflow-hidden;
    
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
