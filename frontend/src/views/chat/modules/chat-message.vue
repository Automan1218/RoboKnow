<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
import AgentReasoning from './agent-reasoning.vue';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('Copied');
}

const chatStore = useChatStore();

// Store source file names for click handling
const sourceFiles = ref<Array<{fileName: string, id: string}>>([]);

// Render source file links
function processSourceLinks(text: string): string {
  // Match "(Source#number: file name)" and the legacy Chinese marker returned by the backend.
  const sourcePattern = /\((?:\u6765\u6e90|Source)#(\d+):\s*([^)]+)\)/g;

  return text.replace(sourcePattern, (_match, sourceNum, fileName) => {
    // Create a clickable link for the file name
    const linkClass = 'source-file-link';
    const encodedFileName = encodeURIComponent(fileName.trim());
    const fileId = `source-file-${sourceFiles.value.length}`;

    // Store file information
    sourceFiles.value.push({
      fileName: encodedFileName,
      id: fileId
    });

    return `(Source #${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${fileName}</span>)`;
  });
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // Only process source links for assistant messages
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// Handle content clicks with event delegation
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // Check whether the clicked target is a file link
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        handleSourceFileClick(file.fileName);
      }
    }
  }
}

// Handle source file clicks
async function handleSourceFileClick(fileName: string) {
  const decodedFileName = decodeURIComponent(fileName);
  console.log('Source file clicked:', decodedFileName);

  try {
    window.$message?.loading(`Fetching download link: ${decodedFileName}`, {
      duration: 0,
      closable: false
    });

    // Call the file download API
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: {
        fileName: decodedFileName,
        token: authStore.token
      },
      baseURL: '/proxy-api'
    });

    window.$message?.destroyAll();

    if (error) {
      window.$message?.error(`File download failed: ${error.response?.data?.message || 'Unknown error'}`);
      return;
    }

    if (data?.downloadUrl) {
      // Open the download link in a new window
      window.open(data.downloadUrl, '_blank');
      window.$message?.success(`Download link opened: ${decodedFileName}`);
    } else {
      window.$message?.error('Could not get download link');
    }
  } catch (err) {
    window.$message?.destroyAll();
    console.error('File download failed:', err);
    window.$message?.error(`File download failed: ${decodedFileName}`);
  }
}
</script>

<template>
  <div class="mb-8 flex-col gap-2">
    <div v-if="msg.role === 'user'" class="flex items-center gap-4">
      <NAvatar class="bg-success">
        <SvgIcon icon="ph:user-circle" class="text-icon-large color-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">{{ authStore.userInfo.username }}</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <div v-else class="flex items-center gap-4">
      <NAvatar class="bg-primary">
        <SystemLogo class="text-6 text-white" />
      </NAvatar>
      <div class="flex-col gap-1">
        <NText class="text-4 font-bold">Brain.ai</NText>
        <NText class="text-3 color-gray-500">{{ formatDate(msg.timestamp) }}</NText>
      </div>
    </div>
    <NText v-if="msg.status === 'pending'">
      <icon-eos-icons:three-dots-loading class="ml-12 mt-2 text-8" />
    </NText>
    <NText v-else-if="msg.status === 'error'" class="ml-12 mt-2 italic">Server is busy. Please try again later.</NText>
    <div v-else-if="msg.role === 'assistant'" class="mt-2 pl-12" @click="handleContentClick">
      <!-- Reasoning chain -->
      <AgentReasoning
        v-if="msg.agentSteps && msg.agentSteps.length > 0"
        :steps="msg.agentSteps"
        :current-state="msg.currentAgentState"
        :is-complete="msg.status === 'finished'"
      />
      <!-- Final answer -->
      <VueMarkdownIt v-if="msg.content" :content="content" />
    </div>
    <NText v-else-if="msg.role === 'user'" class="ml-12 mt-2 text-4">{{ content }}</NText>
    <NDivider class="ml-12 w-[calc(100%-3rem)] mb-0! mt-2!" />
    <div class="ml-12 flex gap-4">
      <NButton quaternary @click="handleCopy(msg.content)">
        <template #icon>
          <icon-mynaui:copy />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }

  &:active {
    color: #096dd9;
  }
}
</style>
