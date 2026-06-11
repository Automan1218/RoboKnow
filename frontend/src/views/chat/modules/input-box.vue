<script setup lang="ts">
const chatStore = useChatStore();
const { input, list, wsStatus, wsData, usageLoading, currentTurnUsage, sessionUsage, activeConvId } = storeToRefs(chatStore);

const latestMessage = computed(() => list.value[list.value.length - 1] ?? {});

const isSending = computed(
  () => latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
);

const sendable = computed(
  () => (!input.value.message && !isSending.value) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

watch(wsData, val => {
  const data = JSON.parse(val);
  const assistant = list.value[list.value.length - 1];

  if (data.type === 'completion' && data.status === 'finished') {
    if (assistant.status !== 'error') assistant.status = 'finished';
    assistant.currentAgentState = undefined;
    window.setTimeout(() => {
      chatStore.refreshUsage();
    }, 400);
    // Refresh session list after first message so title updates from "New conversation"
    window.setTimeout(() => {
      chatStore.loadSessions();
    }, 3000);
    return;
  }
  if (data.error) {
    assistant.status = 'error';
    return;
  }
  if (data.chunk) {
    assistant.status = 'loading';
    assistant.content += data.chunk;
    return;
  }

  if (!assistant.agentSteps) assistant.agentSteps = [];

  if (data.type === 'agent_state') {
    assistant.status = 'loading';
    assistant.currentAgentState = data.state;
    if (data.state === 'THINKING' && data.iteration > 0) {
      const exists = assistant.agentSteps.some((s: Api.Chat.AgentStep) => s.iteration === data.iteration);
      if (!exists) assistant.agentSteps.push({ iteration: data.iteration });
    }
  } else if (data.type === 'thought') {
    assistant.status = 'loading';
    const last = assistant.agentSteps.at(-1) as Api.Chat.AgentStep | undefined;
    if (last) last.thought = data.content;
  } else if (data.type === 'action') {
    assistant.status = 'loading';
    const last = assistant.agentSteps.at(-1) as Api.Chat.AgentStep | undefined;
    if (last) { last.action = data.tool; last.actionInput = data.input; }
  } else if (data.type === 'observation') {
    assistant.status = 'loading';
    const last = assistant.agentSteps.at(-1) as Api.Chat.AgentStep | undefined;
    if (last) last.observation = data.content;
  }
});

const handleSend = async () => {
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token', baseURL: 'proxy-api' });
    if (error) return;
    // Stop command uses raw send — bypass JSON message wrapper
    chatStore.wsRawSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));
    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  const message = input.value.message;
  await chatStore.prepareCurrentTurnUsage();
  list.value.push({ content: message, role: 'user' });
  // wsSend sends JSON { message, convId } so backend routes to the correct session
  chatStore.wsSend(message);
  list.value.push({ content: '', role: 'assistant', status: 'pending' });
  input.value.message = '';
};

const inputRef = ref<HTMLTextAreaElement>();

const insertNewline = () => {
  const textarea = inputRef.value;
  if (!textarea) return;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus();
  });
};

const handleKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else {
      insertNewline();
    }
  }
};

const wsStatusColor = computed(() => {
  if (wsStatus.value === 'OPEN') return 'text-green-500';
  if (wsStatus.value === 'CONNECTING') return 'text-yellow-500';
  return 'text-red-500';
});

const wsStatusLabel = computed(() => {
  if (wsStatus.value === 'OPEN') return 'Connected';
  if (wsStatus.value === 'CONNECTING') return 'Connecting…';
  return 'Disconnected';
});

const hasCurrentTurnUsage = computed(() => currentTurnUsage.value && currentTurnUsage.value.requestCount > 0);

function formatTokenCount(value?: number) {
  return new Intl.NumberFormat().format(value ?? 0);
}

onMounted(() => {
  chatStore.initUsageBaseline();
});
</script>

<template>
  <div class="px-4 pb-4 pt-2">
    <!-- Centered container with max-width like Claude -->
    <div class="mx-auto w-full max-w-3xl">
      <div
        class="relative flex flex-col rounded-2xl border bg-container shadow-lg transition-shadow focus-within:border-primary/60 focus-within:shadow-primary/10 dark:bg-[#1c1c1c]"
        :class="isSending ? 'border-primary/40' : 'border-gray/20'"
      >
        <!-- Textarea -->
        <textarea
          ref="inputRef"
          v-model.trim="input.message"
          placeholder="Message RoboKnow…"
          rows="1"
          class="max-h-40 min-h-[52px] w-full resize-none bg-transparent px-4 pt-3.5 pb-2 text-sm leading-relaxed outline-none"
          style="field-sizing: content"
          @keydown="handleKeydown"
        />

        <!-- Bottom row: status + send -->
        <div class="flex items-center justify-between px-3 pb-3">
          <!-- Connection status -->
          <div class="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs">
            <div class="flex items-center gap-1.5" :class="wsStatusColor">
              <icon-eos-icons:loading v-if="wsStatus === 'CONNECTING'" class="animate-spin text-sm" />
              <icon-fluent:plug-connected-checkmark-20-filled v-else-if="wsStatus === 'OPEN'" class="text-sm" />
              <icon-tabler:plug-connected-x v-else class="text-sm" />
              <span class="text-gray-400">{{ wsStatusLabel }}</span>
            </div>

            <div v-if="activeConvId" class="hidden items-center gap-1 text-gray-400 sm:flex">
              <icon-solar:chat-square-linear class="text-xs text-primary/60" />
              <span class="max-w-[120px] truncate font-mono text-[10px] text-gray-400">{{ activeConvId.slice(0, 8) }}…</span>
            </div>

            <div class="flex items-center gap-1.5 text-gray-400">
              <icon-solar:chart-square-linear class="text-sm text-primary/80" />
              <span v-if="usageLoading">Measuring tokens…</span>
              <span v-else-if="hasCurrentTurnUsage">
                Current {{ formatTokenCount(currentTurnUsage?.totalTokens) }}
                <span class="text-gray-500">
                  (P {{ formatTokenCount(currentTurnUsage?.promptTokens) }} / C {{ formatTokenCount(currentTurnUsage?.completionTokens) }})
                </span>
              </span>
              <span v-else>Current 0 tokens</span>
              <span class="hidden text-gray-500 sm:inline">
                · Session {{ formatTokenCount(sessionUsage.totalTokens) }}
              </span>
            </div>
          </div>

          <div class="flex items-center gap-2">
            <span class="hidden text-xs text-gray-500 sm:block">
              {{ isSending ? 'Shift+Enter: new line' : 'Enter to send · Shift+Enter: new line' }}
            </span>

            <!-- Send / Stop button -->
            <NButton
              :disabled="sendable"
              strong
              circle
              type="primary"
              size="small"
              @click="handleSend"
            >
              <template #icon>
                <icon-material-symbols:stop-rounded v-if="isSending" />
                <icon-guidance:send v-else />
              </template>
            </NButton>
          </div>
        </div>
      </div>

      <p class="mt-2 text-center text-xs text-gray-500">
        RoboKnow can make mistakes. Consider checking important information.
      </p>
    </div>
  </div>
</template>

<style scoped></style>
