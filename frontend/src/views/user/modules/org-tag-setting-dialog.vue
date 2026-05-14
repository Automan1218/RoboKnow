<script setup lang="ts">
import type { FormRules } from 'naive-ui';

defineOptions({
  name: 'OrgTagSettingDialog'
});

const props = defineProps<{
  rowData: Api.User.Item;
}>();

const emit = defineEmits<{ submitted: [] }>();

const visible = defineModel<boolean>('visible', { default: false });
const loading = ref(false);
const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

type Model = {
  orgTags: string[];
};

const model = ref<Model>(createDefaultModel());

function createDefaultModel(): Model {
  return {
    orgTags: []
  };
}

const rules = ref<FormRules>({
  orgTags: defaultRequiredRule
});

const privateOrgTag = ref<string[]>([]);
async function handleUpdateModelWhenEdit() {
  model.value = createDefaultModel();
  model.value.orgTags = props.rowData.orgTags.map(tag => tag.tagId!);
  // Keep private organization tags so they cannot be removed by mistake
  privateOrgTag.value = props.rowData.orgTags.filter(tag => tag.tagId!.startsWith('PRIVATE_')).map(tag => tag.tagId!);
}

function close() {
  visible.value = false;
}

async function handleSubmit() {
  await validate();
  loading.value = true;
  model.value.orgTags = Array.from(new Set([...model.value.orgTags, ...privateOrgTag.value]));
  const res = await request({
    method: 'PUT',
    url: `/admin/users/${props.rowData.userId}/org-tags`,
    data: model.value
  });
  if (!res.error) {
    window.$message?.success('Operation successful');
    close();
    emit('submitted');
  }
  loading.value = false;
}

watch(visible, () => {
  if (visible.value) {
    handleUpdateModelWhenEdit();
    restoreValidation();
  }
});
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="Organization Tag Settings"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
      <NFormItem label="Username" path="username">
        <NInput :value="rowData.username" readonly />
      </NFormItem>
      <NFormItem label="Organization Tags" path="orgTags">
        <OrgTagCascader v-model:value="model.orgTags" multiple exclude-private />
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">Cancel</NButton>
        <NButton type="primary" @click="handleSubmit">Save</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
