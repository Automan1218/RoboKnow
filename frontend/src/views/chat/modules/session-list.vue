<script setup lang="ts">
defineOptions({ name: 'SessionList' });

const chatStore = useChatStore();
const { sessions, activeConvId, sessionsLoading } = storeToRefs(chatStore);

onMounted(() => {
  chatStore.loadSessions();
});

function formatTime(iso: string) {
  if (!iso) return '';
  const d = dayjs(iso);
  const now = dayjs();
  if (d.isSame(now, 'day')) return d.format('HH:mm');
  if (d.isSame(now.subtract(1, 'day'), 'day')) return 'Yesterday';
  return d.format('MM/DD');
}

async function handleNew() {
  await chatStore.newSession();
}

async function handleSwitch(convId: string) {
  await chatStore.switchToSession(convId);
}

async function handleDelete(e: MouseEvent, convId: string) {
  e.stopPropagation();
  await chatStore.removeSession(convId);
}
</script>

<template>
  <div class="flex w-60 shrink-0 flex-col bg-[#171717] dark:bg-[#171717]">
    <!-- New chat button -->
    <div class="px-3 pt-4 pb-2">
      <button
        class="flex w-full items-center gap-2.5 rounded-lg px-3 py-2.5 text-sm text-white/80 transition-colors hover:bg-white/10 active:bg-white/15"
        :disabled="sessionsLoading"
        @click="handleNew"
      >
        <icon-solar:add-square-linear class="text-base text-white/60" />
        <span class="font-medium">New conversation</span>
        <icon-solar:pen-new-square-linear v-if="!sessionsLoading" class="ml-auto text-base text-white/40" />
        <icon-eos-icons:loading v-else class="ml-auto animate-spin text-base text-white/40" />
      </button>
    </div>

    <!-- Session list -->
    <div class="min-h-0 flex-1 overflow-y-auto px-2 pb-4">
      <!-- Empty state -->
      <div
        v-if="sessions.length === 0 && !sessionsLoading"
        class="flex flex-col items-center gap-2 py-12 text-center"
      >
        <icon-solar:chat-round-dots-linear class="text-2xl text-white/20" />
        <p class="text-xs text-white/30">No conversations yet</p>
      </div>

      <template v-else>
        <p class="mb-1 px-2 text-[10px] font-semibold uppercase tracking-wider text-white/30">Recent</p>
        <div
          v-for="session in sessions"
          :key="session.convId"
          class="group mb-0.5 flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 transition-colors"
          :class="session.convId === activeConvId
            ? 'bg-white/10 text-white'
            : 'text-white/70 hover:bg-white/8 hover:text-white'"
          @click="handleSwitch(session.convId)"
        >
          <div class="min-w-0 flex-1">
            <p class="truncate text-sm leading-tight">
              {{ session.title || 'New conversation' }}
            </p>
            <p class="text-[10px] text-white/30">{{ formatTime(session.lastActiveAt) }}</p>
          </div>

          <NButton
            size="tiny"
            quaternary
            circle
            class="invisible shrink-0 group-hover:visible"
            :class="{ 'visible': session.convId === activeConvId }"
            @click="(e: MouseEvent) => handleDelete(e, session.convId)"
          >
            <template #icon>
              <icon-solar:trash-bin-minimalistic-linear class="text-xs text-white/30 hover:text-red-400" />
            </template>
          </NButton>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped></style>
