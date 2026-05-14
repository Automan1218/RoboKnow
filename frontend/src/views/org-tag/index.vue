<script setup lang="tsx">
import { NButton, NPopconfirm } from 'naive-ui';
import OrgTagOperateDialog from './modules/org-tag-operate-dialog.vue';

const appStore = useAppStore();

const { columns, columnChecks, data, loading, getData } = useTable({
  apiFn: fetchGetOrgTagList,
  columns: () => [
    {
      key: 'name',
      title: 'Tag Name',
      width: 300,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'description',
      title: 'Description',
      minWidth: 200,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'operate',
      title: 'Actions',
      width: 240,
      render: row => (
        <div class="flex gap-2">
          <NButton type="success" ghost size="small" onClick={() => addChild(row)}>
            Add Child
          </NButton>
          <NButton type="primary" ghost size="small" onClick={() => edit(row)}>
            Edit
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.tagId!)}>
            {{
              default: () => 'Delete this tag?',
              trigger: () => (
                <NButton type="error" ghost size="small">
                  Delete
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const {
  dialogVisible,
  operateType,
  editingData,
  handleAdd,
  handleAddChild,
  handleEdit,
  onDeleted
  // closeDrawer
} = useTableOperate<Api.OrgTag.Item>(getData);

function addChild(row: Api.OrgTag.Item) {
  handleAddChild(row);
}

/** the editing row data */
function edit(row: Api.OrgTag.Item) {
  handleEdit(row);
}

async function handleDelete(tagId: string) {
  const { error } = await request({ url: `/admin/org-tags/${tagId}`, method: 'DELETE' });
  if (!error) {
    onDeleted();
  }
}
</script>

<template>
  <div class="flex-col-stretch gap-16px overflow-hidden <sm:overflow-auto">
    <NCard title="Organization Tags" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleAdd" @refresh="getData" />
      </template>
      <NDataTable
        remote
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        :pagination="false"
        :row-key="item => item.tagId"
        class="sm:h-full"
      />
      <OrgTagOperateDialog
        v-model:visible="dialogVisible"
        :operate-type="operateType"
        :row-data="editingData!"
        :data="data"
        @submitted="getData"
      />
    </NCard>
  </div>
</template>

<style scoped></style>
