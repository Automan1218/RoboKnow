<script setup lang="ts">
const chatStore = useChatStore();
const { input, list, wsStatus, wsData } = storeToRefs(chatStore);

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendable = computed(
  () => (!input.value.message && !isSending) || ['CLOSED', 'CONNECTING'].includes(wsStatus.value)
);

watch(wsData, val => {
  const data = JSON.parse(val);
  const assistant = list.value[list.value.length - 1];

  // Conversation completed
  if (data.type === 'completion' && data.status === 'finished') {
    if (assistant.status !== 'error') assistant.status = 'finished';
    assistant.currentAgentState = undefined;
    return;
  }

  // Error
  if (data.error) {
    assistant.status = 'error';
    return;
  }

  // Final answer streaming chunk
  if (data.chunk) {
    assistant.status = 'loading';
    assistant.content += data.chunk;
    return;
  }

  // Agent reasoning event
  if (!assistant.agentSteps) assistant.agentSteps = [];

  if (data.type === 'agent_state') {
    assistant.status = 'loading';
    assistant.currentAgentState = data.state;
    // Create a new step whenever a THINKING round starts
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
  // Stop the current AI response if one is in progress
  if (isSending.value) {
    const { error, data } = await request<Api.Chat.Token>({ url: 'chat/websocket-token', baseURL: 'proxy-api' });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  chatStore.wsSend(input.value.message);
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending'
  });
  input.value.message = '';
};

const inputRef = ref();
// Insert a newline manually for consistent browser behavior
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // Insert a newline at the cursor position
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // Move the cursor after the inserted newline
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // Keep focus in the textarea
  });
};

// Ctrl + Enter inserts a newline
// Enter sends the message
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};
</script>

<template>
  <div class="relative w-full b-1 b-#1c1c1c20 bg-#fff p-4 card-wrapper dark:bg-#1c1c1c">
    <textarea
      ref="inputRef"
      v-model.trim="input.message"
      placeholder="Message Brain.ai"
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
    />
    <div class="flex items-center justify-between pt-2">
      <div class="flex items-center text-18px color-gray-500">
        <NText class="text-14px">Connection:</NText>
        <icon-eos-icons:loading v-if="wsStatus === 'CONNECTING'" class="color-yellow" />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="wsStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
      </div>
      <NButton :disabled="sendable" strong circle type="primary" @click="handleSend">
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" />
          <icon-guidance:send v-else />
        </template>
      </NButton>
    </div>
  </div>
</template>

<style scoped></style>
