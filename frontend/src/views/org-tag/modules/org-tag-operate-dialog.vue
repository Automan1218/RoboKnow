<script setup lang="ts">
import type { FormRules } from 'naive-ui';
import type { FlatResponseData } from '~/packages/axios/src';

defineOptions({
  name: 'OrgTagOperateDialog'
});

const props = defineProps<{
  operateType: NaiveUI.TableOperateType;
  rowData: Api.OrgTag.Item;
  data: Api.OrgTag.Item[];
}>();

const emit = defineEmits<{ submitted: [] }>();

const visible = defineModel<boolean>('visible', { default: false });
const loading = ref(false);
const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const title = computed(() => {
  const titles: Record<NaiveUI.TableOperateType, string> = {
    add: 'Add',
    edit: 'Edit',
    addChild: 'Add Child'
  };
  return titles[props.operateType];
});

const model = ref<Api.OrgTag.Item>(createDefaultModel());

function createDefaultModel(): Api.OrgTag.Item {
  return {
    tagId: '',
    name: '',
    description: '',
    parentTag: null
  };
}

const rules = ref<FormRules>({
  tagId: [
    defaultRequiredRule,
    {
      validator(_, value) {
        return !value.startsWith('PRIVATE_');
      },
      message: 'Tag ID cannot start with PRIVATE_',
      trigger: 'blur'
    }
  ],
  name: defaultRequiredRule,
  description: defaultRequiredRule
});

async function handleUpdateModelWhenEdit() {
  model.value = createDefaultModel();

  if (props.operateType === 'edit') model.value = props.rowData;
  else if (props.operateType === 'addChild') model.value.parentTag = props.rowData.tagId!;
}

function close() {
  visible.value = false;
}

async function handleSubmit() {
  await validate();
  loading.value = true;
  let res: FlatResponseData;
  if (props.operateType === 'edit')
    res = await request({ url: `/admin/org-tags/${model.value.tagId}`, method: 'PUT', data: model.value });
  else res = await request({ url: '/admin/org-tags', method: 'POST', data: model.value });
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
    :title="title"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
      <NFormItem label="Tag ID" path="tagId">
        <NInput v-model:value="model.tagId" placeholder="Enter tag ID" maxlength="60" />
      </NFormItem>
      <NFormItem label="Tag Name" path="name">
        <NInput v-model:value="model.name" placeholder="Enter tag name" maxlength="60" />
      </NFormItem>
      <NFormItem label="Parent Tag" path="parentTag">
        <OrgTagCascader v-model:value="model.parentTag" :options="data" />
      </NFormItem>
      <NFormItem label="Description" path="description">
        <NInput
          v-model:value="model.description"
          type="textarea"
          placeholder="Enter tag description"
          maxlength="300"
          clearable
          show-count
          :autosize="{ minRows: 3, maxRows: 10 }"
        />
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
