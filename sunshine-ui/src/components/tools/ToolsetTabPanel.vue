<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NDataTable,
  NEmpty,
  NIcon,
  NInput,
  NPagination,
  NSpace,
  NSpin,
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
  useMessage,
  useDialog,
  type DataTableColumns,
} from 'naive-ui'
import { AddOutline, SearchOutline, TrashOutline } from '@vicons/ionicons5'
import TenantSelector from '../knowledge/TenantSelector.vue'
import ToolSetAddModal from './ToolSetAddModal.vue'
import {
  pageToolSetMembers,
  patchPlanWorkflowMemberCritical,
  removeToolSetMembers,
  type ToolSetKindPath,
  type ToolSetMemberItem,
} from '../../api/tools'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import { useTenantPreference } from '../../composables/useTenantPreference'

const message = useMessage()
const dialog = useDialog()
const { tenantId: toolsetTenant, setTenantId: setToolsetTenant } = useTenantPreference()

let membersRequestSeq = 0

const subTab = ref<'react' | 'plan-workflow'>('react')
const loading = ref(false)
const searchQuery = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const members = ref<ToolSetMemberItem[]>([])
const selectedRowKeys = ref<string[]>([])
const showAddModal = ref(false)

const kind = computed<ToolSetKindPath>(() =>
  subTab.value === 'react' ? 'react-default' : 'plan-workflow',
)

const tenantParam = computed(() =>
  toolsetTenant.value === 'default' ? undefined : toolsetTenant.value,
)

const tableScrollX = computed(() => (subTab.value === 'plan-workflow' ? 880 : 800))

function renderCellText(text: string) {
  return h('span', { class: 'toolset-cell-text' }, text)
}

const columns = computed((): DataTableColumns<ToolSetMemberItem> => {
  const base: DataTableColumns<ToolSetMemberItem> = [
    { type: 'selection', fixed: 'left', width: 44 },
    {
      title: '展示名',
      key: 'displayName',
      minWidth: 280,
      ellipsis: false,
      render: (row) => renderCellText(row.displayName),
    },
    {
      title: '来源',
      key: 'sourceLabel',
      minWidth: 240,
      ellipsis: false,
      render: (row) => renderCellText(row.sourceLabel),
    },
    {
      title: '读写',
      key: 'sideEffect',
      width: 60,
      align: 'center',
      render: (row) => h(NTag, {
        size: 'small',
        bordered: false,
        type: row.sideEffect === 'write' ? 'warning' : 'default',
      }, { default: () => (row.sideEffect === 'write' ? '写' : '读') }),
    },
  ]
  if (subTab.value === 'plan-workflow') {
    base.push({
      title: '关键',
      key: 'critical',
      width: 72,
      align: 'center',
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.critical,
        onUpdateValue: (v: boolean) => handlePatchCritical(row, v),
      }),
    })
  }
  base.push({
    title: '操作',
    key: 'actions',
    fixed: 'right',
    width: 56,
    align: 'center',
    render: (row) => h(NButton, {
      size: 'small',
      quaternary: true,
      circle: true,
      type: 'error',
      title: '从工具集移除',
      onClick: () => confirmRemove([row.toolId]),
    }, {
      icon: () => h(NIcon, { component: TrashOutline, size: 16 }),
    }),
  })
  return base
})

async function refreshMembers() {
  const seq = ++membersRequestSeq
  loading.value = true
  try {
    const resp = await pageToolSetMembers(
      kind.value,
      tenantParam.value,
      page.value,
      pageSize.value,
      searchQuery.value,
    )
    if (seq !== membersRequestSeq) return
    members.value = resp.items ?? []
    total.value = resp.total ?? 0
    selectedRowKeys.value = []
  } catch (e) {
    if (seq !== membersRequestSeq) return
    message.error(friendlyErrorMessage(e, '加载工具集失败'))
    console.error(e)
  } finally {
    if (seq === membersRequestSeq) loading.value = false
  }
}

async function refreshAll() {
  await refreshMembers()
}

function confirmRemove(toolIds: string[]) {
  if (!toolIds.length) return
  const count = toolIds.length
  const single = count === 1
    ? members.value.find(m => m.toolId === toolIds[0])
    : undefined
  const name = single?.displayName || toolIds[0]
  dialog.warning({
    class: 'sunshine-dialog',
    title: single ? '移除工具' : '批量移除工具',
    content: single
      ? `确定从工具集中移除「${name}」吗？\n仅从本工具集剔除，不影响 Catalog 池中的工具状态。`
      : `确定从工具集中移除已选的 ${count} 个工具吗？\n仅从本工具集剔除，不影响 Catalog 池中的工具状态。`,
    positiveText: '移除',
    negativeText: '取消',
    positiveButtonProps: { type: 'error', size: 'medium' },
    negativeButtonProps: { ghost: false, quaternary: true, size: 'medium' },
    onPositiveClick: () => { void handleRemove(toolIds) },
  })
}

async function handleRemove(toolIds: string[]) {
  if (!toolIds.length) return
  try {
    await removeToolSetMembers(kind.value, toolIds, tenantParam.value)
    message.success(toolIds.length === 1 ? '已移除' : `已移除 ${toolIds.length} 个工具`)
    await refreshMembers()
  } catch (e) {
    message.error('移除失败')
    console.error(e)
  }
}

async function handlePatchCritical(row: ToolSetMemberItem, critical: boolean) {
  try {
    await patchPlanWorkflowMemberCritical(row.toolId, critical, tenantParam.value)
    row.critical = critical
    message.success(critical ? '已设为关键工具' : '已取消关键工具')
  } catch (e) {
    message.error('更新关键标记失败')
    console.error(e)
    await refreshMembers()
  }
}

watch(subTab, () => {
  page.value = 1
  void refreshAll()
})

watch(toolsetTenant, () => {
  page.value = 1
  void refreshAll()
})

let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(searchQuery, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 1
    void refreshMembers()
  }, 300)
})

watch(page, (next, prev) => {
  if (next === prev) return
  void refreshMembers()
})
watch(pageSize, (next, prev) => {
  if (next === prev) return
  page.value = 1
  void refreshMembers()
})

onMounted(() => { void refreshAll() })

defineExpose({ refresh: refreshAll })
</script>

<template>
  <main class="toolset-panel detail-panel full-width">
    <NTabs v-model:value="subTab" type="line" animated class="toolset-subtabs">
      <NTabPane name="react" tab="ReAct" />
      <NTabPane name="plan-workflow" tab="Planner Workflow" />
    </NTabs>
    <div class="toolset-toolbar">
      <div class="toolset-toolbar-head">
        <h3 class="toolset-heading">
          工具集<span class="toolset-stats"> · 集内 {{ total }} 条</span>
        </h3>
        <NSpace :size="12" align="center" class="toolset-toolbar-actions">
          <TenantSelector
            :model-value="toolsetTenant"
            variant="compact"
            @update:model-value="(v: TenantId) => setToolsetTenant(v)"
          />
          <NButton size="medium" type="primary" round @click="showAddModal = true">
            <template #icon><NIcon :component="AddOutline" /></template>
            添加工具
          </NButton>
        </NSpace>
      </div>
      <NInput
        v-model:value="searchQuery"
        size="medium"
        clearable
        class="toolset-search sun-field"
        placeholder="搜索工具、ID 或 SDK/MCP 名称…"
      >
        <template #prefix>
          <NIcon :component="SearchOutline" :size="16" />
        </template>
      </NInput>
    </div>
    <NSpin :show="loading" class="toolset-spin">
      <div class="toolset-body">
        <div v-if="total > 0" class="toolset-table-wrap">
          <div v-if="selectedRowKeys.length" class="toolset-batch-bar">
            <NButton size="small" quaternary type="error" @click="confirmRemove(selectedRowKeys)">
              <template #icon><NIcon :component="TrashOutline" :size="16" /></template>
              批量移除（{{ selectedRowKeys.length }}）
            </NButton>
          </div>
          <div class="toolset-table-scroll">
            <NDataTable
              :columns="columns"
              :data="members"
              :row-key="(row: ToolSetMemberItem) => row.toolId"
              v-model:checked-row-keys="selectedRowKeys"
              :scroll-x="tableScrollX"
              :bordered="false"
              flex-height
              size="small"
              class="tools-table toolset-table"
            />
          </div>
          <footer class="toolset-table-footer">
            <NPagination
              v-model:page="page"
              v-model:page-size="pageSize"
              :item-count="total"
              :page-sizes="[10, 20, 50]"
              show-size-picker
              show-quick-jumper
              class="toolset-pagination"
            />
          </footer>
        </div>
        <div v-else class="toolset-empty-wrap">
          <NEmpty size="large" description="尚未添加工具" class="toolset-empty" />
        </div>
      </div>
    </NSpin>
    <ToolSetAddModal
      v-model:show="showAddModal"
      :kind="kind"
      :tenant-id="toolsetTenant"
      :allow-critical="subTab === 'plan-workflow'"
      @added="refreshMembers"
    />
  </main>
</template>

<style scoped>
.toolset-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.toolset-subtabs {
  padding: 0 20px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.toolset-subtabs :deep(.n-tabs-nav) {
  background: transparent;
}

.toolset-toolbar {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.toolset-toolbar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.toolset-heading {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.toolset-stats {
  font-size: 13px;
  font-weight: 400;
  color: var(--sun-text-secondary);
}

.toolset-toolbar-actions {
  flex-shrink: 0;
}

.toolset-search {
  width: 100%;
}

.toolset-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.toolset-spin :deep(.n-spin-container) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.toolset-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.toolset-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 12px 24px 16px;
  overflow: hidden;
}

.toolset-table-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
}

.toolset-table-scroll {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.toolset-table {
  height: 100%;
}

.toolset-table :deep(.n-data-table) {
  --n-th-color: var(--sun-black);
  --n-td-color: var(--sun-black);
  --n-border-color: var(--sun-border);
}

.toolset-table :deep(.n-data-table-th) {
  white-space: nowrap;
  font-weight: 600;
  border-bottom: 1px solid var(--sun-border);
}

.toolset-table :deep(.n-data-table-tr:not(:last-child) .n-data-table-td) {
  border-bottom: 1px solid var(--sun-border);
}

.toolset-table :deep(.n-data-table-th),
.toolset-table :deep(.n-data-table-td) {
  padding: 10px 12px;
  vertical-align: top;
}

.toolset-table :deep(.n-data-table-td) {
  white-space: normal;
  word-break: break-word;
}

.toolset-table :deep(.toolset-cell-text) {
  display: block;
  line-height: 1.45;
  word-break: break-word;
}

.toolset-table :deep(.n-data-table-td--last-col) {
  padding-left: 8px;
  padding-right: 8px;
}

.toolset-empty-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.toolset-batch-bar {
  flex-shrink: 0;
  padding: 0 2px;
}

.toolset-empty {
  padding: 0;
}

.toolset-table-footer {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 4px 0 0;
}

.toolset-pagination {
  justify-content: flex-end;
}
</style>
