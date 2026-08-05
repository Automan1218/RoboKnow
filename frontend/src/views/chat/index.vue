<script setup lang="ts">
import { NDrawer, NDrawerContent } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import FilePreview from '@/components/custom/file-preview.vue';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import SessionList from './modules/session-list.vue';

const chatStore = useChatStore();
const appStore = useAppStore();

const previewVisible = computed(() => Boolean(chatStore.previewFileName));

function closePreview() {
  chatStore.previewFileName = '';
}

// Collapse app nav to icon rail when in chat; restore on leave
let prevCollapse = false;
onMounted(() => {
  prevCollapse = appStore.siderCollapse;
  appStore.setSiderCollapse(true);
});
onUnmounted(() => {
  appStore.setSiderCollapse(prevCollapse);
});
</script>

<template>
  <div class="flex min-h-0 overflow-hidden">
    <!-- Session sidebar (GPT/Claude style) -->
    <SessionList />

    <!-- Main chat area -->
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden">
      <ChatList class="min-h-0 flex-1" />
      <InputBox />
    </div>
  </div>

  <NDrawer
    :show="previewVisible"
    :width="600"
    placement="right"
    :trap-focus="false"
    :block-scroll="false"
    @update:show="val => { if (!val) closePreview(); }"
  >
    <NDrawerContent :native-scrollbar="false" body-content-style="padding:0; height:100%;">
      <FilePreview
        :file-name="chatStore.previewFileName"
        :visible="previewVisible"
        @close="closePreview"
      />
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped></style>
