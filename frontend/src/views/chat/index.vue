<script setup lang="ts">
import { NDrawer, NDrawerContent } from 'naive-ui';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import FilePreview from '@/components/custom/file-preview.vue';

const chatStore = useChatStore();

const previewVisible = computed(() => !!chatStore.previewFileName);

function closePreview() {
  chatStore.previewFileName = '';
}
</script>

<template>
  <div class="flex-col gap-4">
    <ChatList />
    <InputBox />
  </div>

  <!-- File preview drawer (slides in from right) -->
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
