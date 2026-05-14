<script setup lang="ts">
import { enableStatusOptions } from '@/constants/common';

defineOptions({
  name: 'UserSearch'
});

const emit = defineEmits<{
  search: [];
}>();

const { formRef } = useNaiveForm();

const model = defineModel<Api.User.SearchParams>('model', { required: true });

watchEffect(() => {
  search();
});
async function search() {
  emit('search');
}
</script>

<template>
  <NCard :bordered="false" size="small" class="rd-full px-6">
    <NForm ref="formRef" :model="model" label-placement="left" :show-feedback="false" inline>
      <NFormItem label="Keyword" path="keyword">
        <NInput v-model:value="model.keyword" placeholder="Enter keyword" clearable />
      </NFormItem>
      <NFormItem label="Organization Tag" path="userGender">
        <OrgTagCascader v-model:value="model.orgTag" clearable class="w-200px!" />
      </NFormItem>
      <NFormItem label="Status" path="status">
        <NSelect
          v-model:value="model.status"
          placeholder="Select status"
          :options="enableStatusOptions"
          clearable
          class="w-200px!"
        />
      </NFormItem>
    </NForm>
  </NCard>
</template>

<style scoped></style>
