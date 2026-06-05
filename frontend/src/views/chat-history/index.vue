<script setup lang="ts">
import type { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from 'vue-markdown-shiki';
import ChatMessage from '../chat/modules/chat-message.vue';

defineOptions({
  name: 'ChatHistory'
});

const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

const list = ref<Api.Chat.Message[]>([]);
const loading = ref(false);

const store = useAuthStore();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
}

const range = ref<[number, number]>([dayjs().subtract(7, 'day').valueOf(), dayjs().add(1, 'day').valueOf()]);
const userId = ref<number>(store.userInfo.id);

const params = computed(() => {
  return {
    userid: userId.value,
    start_date: dayjs(range.value[0]).format('YYYY-MM-DD'),
    end_date: dayjs(range.value[1]).format('YYYY-MM-DD')
  };
});

watchEffect(() => {
  getList();
});

async function getList() {
  if (!params.value.userid) return;
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'admin/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
    scrollToBottom();
  }
  loading.value = false;
}
</script>

<template>
  <div class="flex flex-col overflow-hidden" style="height:100%">
    <Teleport defer to="#header-extra">
      <div class="px-10">
        <NForm :model="params" label-placement="left" :show-feedback="false" inline>
          <NFormItem label="User">
            <TheSelect
              v-model:value="userId"
              url="admin/users/list"
              :params="{ page: 1, size: 999, orgTag: store.userInfo.primaryOrg }"
              key-field="content"
              value-field="userId"
              label-field="username"
              class="clear w-200px!"
              :clearable="false"
            />
          </NFormItem>
          <NFormItem label="Date">
            <NDatePicker v-model:value="range" type="daterange" class="clear" />
          </NFormItem>
        </NForm>
      </div>
    </Teleport>

    <div class="flex-1 overflow-y-auto">
      <NSpin :show="loading">
        <!-- Empty state -->
        <div v-if="!loading && !list.length" class="flex min-h-[60vh] flex-col items-center justify-center gap-4">
          <icon-solar:chat-round-dots-linear class="text-6xl text-gray-400" />
          <p class="text-gray-400">No conversation history found for the selected date range.</p>
        </div>

        <div v-else class="mx-auto w-full max-w-3xl px-4 py-6">
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
