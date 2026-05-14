<script setup lang="ts">
defineOptions({ name: 'AgentReasoning' });

const props = defineProps<{
  steps: Api.Chat.AgentStep[];
  currentState?: string;
  isComplete?: boolean;
}>();

// Collapse automatically after completion
const expanded = ref(true);
watch(
  () => props.isComplete,
  val => { if (val) expanded.value = false; }
);

const toolLabel: Record<string, string> = {
  hybrid_search: 'Hybrid Search',
  summarize: 'Document Summary',
  metadata_filter: 'Metadata Filter'
};

const toolIcon: Record<string, string> = {
  hybrid_search: '🔍',
  summarize: '📝',
  metadata_filter: '🔎'
};

const headerText = computed(() => {
  if (props.isComplete || props.currentState === 'ANSWERING' || props.currentState === 'FINISHED') {
    return `Reasoning complete (${props.steps.length} rounds)`;
  }
  const stateMap: Record<string, string> = {
    THINKING: 'Thinking...',
    ACTING: 'Calling tool...',
    OBSERVING: 'Analyzing results...'
  };
  return stateMap[props.currentState ?? ''] ?? 'Reasoning...';
});

const isProcessing = computed(
  () => !props.isComplete && props.currentState !== 'ANSWERING' && props.currentState !== 'FINISHED'
);

/** Whether this is the current active step */
function isActive(step: Api.Chat.AgentStep) {
  return isProcessing.value && step.iteration === props.steps.length;
}
</script>

<template>
  <div class="mb-4 overflow-hidden rounded-lg" style="border: 1px solid #e5e7eb; background: #f9fafb;">
    <!-- Header / collapse button -->
    <div
      class="flex cursor-pointer select-none items-center justify-between px-4 py-2"
      style="background: #f3f4f6;"
      @click="expanded = !expanded"
    >
      <div class="flex items-center gap-2">
        <span class="text-sm">🧠</span>
        <span class="text-sm font-medium" style="color: #374151;">{{ headerText }}</span>
        <icon-eos-icons:loading v-if="isProcessing" class="text-sm" style="color: #9ca3af;" />
      </div>
      <span class="text-xs" style="color: #9ca3af;">{{ expanded ? '▲' : '▼' }}</span>
    </div>

    <!-- Reasoning steps -->
    <Transition name="slide">
      <div v-if="expanded" class="flex-col divide-y divide-gray-100 px-4 py-3">
        <div v-for="step in steps" :key="step.iteration" class="flex-col gap-2 py-3 first:pt-0 last:pb-0">
          <!-- Round title -->
          <div class="flex items-center gap-2">
            <span
              class="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-full text-white"
              style="background: #3b82f6; font-size: 10px;"
            >{{ step.iteration }}</span>
            <span class="text-xs font-semibold" style="color: #1f2937;">Round {{ step.iteration }}</span>
            <icon-eos-icons:loading
              v-if="isActive(step) && currentState === 'THINKING'"
              class="text-xs"
              style="color: #9ca3af;"
            />
          </div>

          <!-- Thought -->
          <div v-if="step.thought" class="flex gap-2 pl-7">
            <span class="mt-0.5 flex-shrink-0 text-xs">💭</span>
            <p class="text-xs leading-relaxed" style="color: #4b5563; word-break: break-all; margin: 0;">
              {{ step.thought }}
            </p>
          </div>
          <div v-else-if="isActive(step) && currentState === 'THINKING'" class="flex items-center gap-1 pl-7">
            <span class="text-xs">💭</span>
            <span class="text-xs italic" style="color: #9ca3af;">Thinking...</span>
          </div>

          <!-- Action -->
          <div v-if="step.action" class="flex gap-2 pl-7">
            <span class="mt-0.5 flex-shrink-0 text-xs">{{ toolIcon[step.action] ?? '⚡' }}</span>
            <div class="flex-col gap-1">
              <span class="text-xs font-medium" style="color: #2563eb;">
                {{ toolLabel[step.action] ?? step.action }}
              </span>
              <div
                v-if="step.actionInput"
                class="overflow-hidden rounded px-2 py-1 text-xs"
                style="background: #f5f5f5; font-family: monospace; max-height: 64px; color: #6b7280; word-break: break-all;"
              >{{ step.actionInput }}</div>
            </div>
          </div>
          <div v-else-if="isActive(step) && currentState === 'ACTING'" class="flex items-center gap-1 pl-7">
            <span class="text-xs">⚡</span>
            <span class="text-xs italic" style="color: #9ca3af;">Calling tool...</span>
            <icon-eos-icons:loading class="text-xs" style="color: #9ca3af;" />
          </div>

          <!-- Observation -->
          <div v-if="step.observation" class="flex gap-2 pl-7">
            <span class="mt-0.5 flex-shrink-0 text-xs">📋</span>
            <div
              class="overflow-y-auto rounded-r px-2 py-1 text-xs leading-relaxed"
              style="background: #f0fdf4; border-left: 3px solid #4ade80; max-height: 80px; color: #374151; word-break: break-all;"
            >{{ step.observation }}</div>
          </div>
          <div v-else-if="isActive(step) && currentState === 'OBSERVING'" class="flex items-center gap-1 pl-7">
            <span class="text-xs">📋</span>
            <span class="text-xs italic" style="color: #9ca3af;">Analyzing results...</span>
            <icon-eos-icons:loading class="text-xs" style="color: #9ca3af;" />
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.slide-enter-active,
.slide-leave-active {
  transition: max-height 0.25s ease, opacity 0.2s ease;
  overflow: hidden;
  max-height: 600px;
}
.slide-enter-from,
.slide-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>
