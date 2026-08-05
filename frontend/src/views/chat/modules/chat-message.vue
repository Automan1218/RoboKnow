<script setup lang="ts">
import { VueMarkdownIt } from 'vue-markdown-shiki';
import { formatDate } from '@/utils/common';
import AgentReasoning from './agent-reasoning.vue';

defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();
const chatStore = useChatStore();

const sourceFiles = ref<Array<{ fileName: string; id: string }>>([]);

function processSourceLinks(text: string): string {
  const sourcePattern = /\((?:来源|Source)#(\d+):\s*([^)]+)\)/g;
  return text.replace(sourcePattern, (_match, sourceNum, fileName) => {
    const fileId = `source-file-${sourceFiles.value.length}`;
    sourceFiles.value.push({ fileName: encodeURIComponent(fileName.trim()), id: fileId });
    return `(Source #${sourceNum}: <span class="source-file-link" data-file-id="${fileId}">${fileName}</span>)`;
  });
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const raw = props.msg.content ?? '';
  return props.msg.role === 'assistant' ? processSourceLinks(raw) : raw;
});

function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) chatStore.previewFileName = decodeURIComponent(file.fileName);
    }
  }
}

function handleCopy() {
  navigator.clipboard.writeText(props.msg.content ?? '');
  window.$message?.success('Copied');
}

const isUser = computed(() => props.msg.role === 'user');
</script>

<template>
  <!-- User message -->
  <div v-if="isUser" class="mb-6 flex justify-end">
    <div class="flex max-w-[80%] flex-col items-end gap-1">
      <div class="flex items-center gap-2">
        <span class="text-xs text-gray-400">{{ formatDate(msg.timestamp) }}</span>
        <span class="text-xs font-medium text-gray-500">{{ authStore.userInfo.username }}</span>
        <NAvatar size="small" class="bg-success shrink-0">
          <SvgIcon icon="ph:user-circle" class="text-icon color-white" />
        </NAvatar>
      </div>
      <div class="rounded-2xl rounded-tr-sm bg-primary px-4 py-3 text-sm text-white shadow-sm">
        <p class="whitespace-pre-wrap leading-relaxed">{{ msg.content }}</p>
      </div>
    </div>
  </div>

  <!-- Assistant message -->
  <div v-else class="mb-6 flex gap-3">
    <NAvatar size="small" class="bg-primary shrink-0 mt-1">
      <SystemLogo class="text-base text-white" />
    </NAvatar>

    <div class="min-w-0 flex-1">
      <div class="mb-1 flex items-center gap-2">
        <span class="text-xs font-medium text-primary">RoboKnow</span>
        <span v-if="msg.timestamp" class="text-xs text-gray-400">{{ formatDate(msg.timestamp) }}</span>
      </div>

      <!-- Loading -->
      <div v-if="msg.status === 'pending'" class="flex items-center gap-1 py-2">
        <span class="h-2 w-2 animate-bounce rounded-full bg-primary/60" style="animation-delay:0ms" />
        <span class="h-2 w-2 animate-bounce rounded-full bg-primary/60" style="animation-delay:150ms" />
        <span class="h-2 w-2 animate-bounce rounded-full bg-primary/60" style="animation-delay:300ms" />
      </div>

      <!-- Error -->
      <NText v-else-if="msg.status === 'error'" class="text-sm italic text-red-400">
        Something went wrong. Please try again.
      </NText>

      <!-- Content -->
      <div v-else class="prose-content" @click="handleContentClick">
        <AgentReasoning
          v-if="msg.agentSteps && msg.agentSteps.length > 0"
          :steps="msg.agentSteps"
          :current-state="msg.currentAgentState"
          :is-complete="msg.status === 'finished'"
        />
        <VueMarkdownIt v-if="msg.content" :content="content" />
      </div>

      <!-- Actions -->
      <div v-if="msg.status !== 'pending'" class="mt-2 flex items-center gap-1">
        <NButton size="tiny" quaternary @click="handleCopy">
          <template #icon>
            <icon-mynaui:copy class="text-sm" />
          </template>
        </NButton>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.prose-content {
  font-size: 0.875rem;
  line-height: 1.75;

  :deep(p) {
    margin-bottom: 0.75rem;
    &:last-child { margin-bottom: 0; }
  }

  :deep(pre) {
    border-radius: 0.5rem;
    margin: 0.75rem 0;
    overflow-x: auto;
  }

  :deep(code:not(pre code)) {
    background: rgba(127, 127, 127, 0.12);
    border-radius: 0.25rem;
    font-size: 0.8em;
    padding: 0.1em 0.4em;
  }

  :deep(ul), :deep(ol) {
    padding-left: 1.5rem;
    margin-bottom: 0.75rem;
  }

  :deep(li) {
    margin-bottom: 0.25rem;
  }

  :deep(.source-file-link) {
    color: rgb(var(--primary-color));
    cursor: pointer;
    text-decoration: underline;
    &:hover { opacity: 0.8; }
  }
}
</style>
