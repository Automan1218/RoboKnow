<script setup lang="ts">
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from './chat-message.vue';

defineOptions({
  name: 'ChatList'
});

const chatStore = useChatStore();
const { list } = storeToRefs(chatStore);

const loading = ref(false);
const scrollRef = ref<HTMLDivElement>();

watch(() => list.value.length, scrollToBottom);
watch(() => list.value[list.value.length - 1]?.content, scrollToBottom);

function scrollToBottom() {
  nextTick(() => {
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight;
    }
  });
}

const range = ref<[number, number]>([dayjs().subtract(7, 'day').valueOf(), dayjs().add(1, 'day').valueOf()]);

const params = computed(() => ({
  start_date: dayjs(range.value[0]).format('YYYY-MM-DD'),
  end_date: dayjs(range.value[1]).format('YYYY-MM-DD')
}));

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});

const suggestions = [
  { icon: 'solar:document-text-linear', text: 'Summarize a document for me' },
  { icon: 'solar:magnifer-linear', text: 'Search the knowledge base' },
  { icon: 'solar:chat-round-call-linear', text: 'Ask me anything' },
  { icon: 'solar:lightbulb-linear', text: 'Help me brainstorm ideas' }
];

function useSuggestion(text: string) {
  chatStore.input.message = text;
}
</script>

<template>
  <div class="flex h-full flex-col overflow-hidden">
    <!-- Header controls teleported to global header -->
    <Teleport defer to="#header-extra">
      <div class="px-10">
        <NForm :model="params" label-placement="left" :show-feedback="false" inline>
          <NFormItem label="Date">
            <NDatePicker v-model:value="range" type="daterange" />
          </NFormItem>
        </NForm>
      </div>
    </Teleport>

    <!-- Messages scrollable area -->
    <div ref="scrollRef" class="flex-1 overflow-y-auto scroll-smooth">
      <NSpin :show="loading">

        <!-- Empty / welcome state -->
        <div
          v-if="!loading && list.length === 0"
          class="flex min-h-[70vh] flex-col items-center justify-center gap-8 px-4 py-16"
        >
          <div class="flex flex-col items-center gap-4 text-center">
            <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10 shadow-sm">
              <SystemLogo class="text-4xl text-primary" />
            </div>
            <div class="space-y-1">
              <h2 class="text-2xl font-semibold">How can I help you today?</h2>
              <p class="text-sm text-gray-400">Ask anything or pick a suggestion below</p>
            </div>
          </div>

          <div class="grid w-full max-w-xl grid-cols-2 gap-3">
            <button
              v-for="s in suggestions"
              :key="s.text"
              class="flex cursor-pointer items-center gap-3 rounded-xl border border-gray/20 bg-container px-4 py-3 text-left text-sm transition-colors hover:border-primary/50 hover:bg-primary/5 active:scale-[0.98]"
              @click="useSuggestion(s.text)"
            >
              <SvgIcon :icon="s.icon" class="shrink-0 text-lg text-primary" />
              <span class="leading-snug">{{ s.text }}</span>
            </button>
          </div>
        </div>

        <!-- Chat messages -->
        <div v-else class="mx-auto w-full max-w-3xl px-4 pb-6 pt-6">
          <Suspense>
            <VueMarkdownItProvider>
              <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
            </VueMarkdownItProvider>
            <template #fallback>
              <div>
                <ChatMessage v-for="(item, index) in list" :key="index" :msg="item" />
              </div>
            </template>
          </Suspense>
        </div>

      </NSpin>
    </div>
  </div>
</template>

<style scoped lang="scss"></style>
