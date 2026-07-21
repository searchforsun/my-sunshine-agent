<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NDataTable,
  NDatePicker,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NSpin,
  NTabPane,
  NTabs,
  useMessage,
  type DataTableColumns,
  type SelectOption,
} from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import {
  createBizRow,
  deleteBizRow,
  listBizRows,
  updateBizRow,
  type BizDomain,
  type BizTable,
} from '../api/bizData'
import { listAuthUsers } from '../api/auth'
import { ApiError } from '../api/apiError'

type FieldKind =
  | 'text'
  | 'number'
  | 'user'
  | 'textarea'
  | 'select'
  | 'date'
  | 'month'
  | 'year'

interface FieldDef {
  key: string
  label: string
  kind: FieldKind
  required?: boolean
  options?: SelectOption[]
  /** 复合主键字段：编辑时若改动则 delete+create */
  compositeKey?: boolean
}

interface TableDef {
  key: BizTable
  label: string
  fields: FieldDef[]
  /** 请求体不含这些键 */
  excludeFromBody?: string[]
}

const DOMAINS: { key: BizDomain; label: string }[] = [
  { key: 'finance', label: '财务' },
  { key: 'hr', label: '人事' },
  { key: 'oa', label: 'OA' },
]

const STATUS_OPTIONS: SelectOption[] = [
  { label: '待处理 pending', value: 'pending' },
  { label: '已通过 approved', value: 'approved' },
  { label: '已驳回 rejected', value: 'rejected' },
]

const TENANT_OPTIONS: SelectOption[] = [
  { label: 'default', value: 'default' },
]

const EXPENSE_CATEGORY_OPTIONS: SelectOption[] = [
  { label: '市内交通', value: '市内交通' },
  { label: '差旅住宿', value: '差旅住宿' },
  { label: '差旅交通', value: '差旅交通' },
  { label: '餐饮', value: '餐饮' },
  { label: '办公用品', value: '办公用品' },
  { label: '其他', value: '其他' },
]

const LEAVE_TYPE_OPTIONS: SelectOption[] = [
  { label: '年假 annual', value: 'annual' },
  { label: '青松假 qingsong', value: 'qingsong' },
  { label: '调休 compensatory', value: 'compensatory' },
]

const OA_CATEGORY_OPTIONS: SelectOption[] = [
  { label: '行政 admin', value: 'admin' },
  { label: '请假 leave', value: 'leave' },
  { label: '合同 contract', value: 'contract' },
  { label: '其他 other', value: 'other' },
]

const TABLE_DEFS: Record<BizDomain, TableDef[]> = {
  finance: [
    {
      key: 'expenses',
      label: '报销单',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'category', label: '类别', kind: 'select', required: true, options: EXPENSE_CATEGORY_OPTIONS },
        { key: 'amount', label: '金额', kind: 'number', required: true },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
        { key: 'occurredOn', label: '发生日', kind: 'date', required: true },
        { key: 'remark', label: '备注', kind: 'textarea' },
      ],
      excludeFromBody: ['id'],
    },
    {
      key: 'inbox',
      label: '财务待办',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'title', label: '标题', kind: 'text', required: true },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
        { key: 'amount', label: '金额', kind: 'number', required: true },
      ],
      excludeFromBody: ['id'],
    },
  ],
  hr: [
    {
      key: 'leave-balances',
      label: '假期余额',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true, compositeKey: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'year', label: '年份', kind: 'year', required: true, compositeKey: true },
        { key: 'annual', label: '年假', kind: 'number', required: true },
        { key: 'qingsong', label: '青松假', kind: 'number', required: true },
        { key: 'compensatory', label: '调休', kind: 'number', required: true },
      ],
    },
    {
      key: 'leave-requests',
      label: '请假单',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'leaveType', label: '假别', kind: 'select', required: true, options: LEAVE_TYPE_OPTIONS },
        { key: 'startDate', label: '开始日', kind: 'date', required: true },
        { key: 'endDate', label: '结束日', kind: 'date', required: true },
        { key: 'reason', label: '事由', kind: 'textarea' },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
      ],
      excludeFromBody: ['id'],
    },
    {
      key: 'attendance-months',
      label: '考勤月报',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true, compositeKey: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'yearMonth', label: '年月', kind: 'month', required: true, compositeKey: true },
        { key: 'lateCount', label: '迟到次数', kind: 'number', required: true },
        { key: 'overtimeHours', label: '加班小时', kind: 'number', required: true },
        { key: 'frostLedgerSummary', label: '霜冻台账', kind: 'textarea' },
      ],
    },
  ],
  oa: [
    {
      key: 'tasks',
      label: 'OA 待办',
      fields: [
        { key: 'assigneeUserId', label: '负责人', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'title', label: '标题', kind: 'text', required: true },
        { key: 'category', label: '类别', kind: 'select', required: true, options: OA_CATEGORY_OPTIONS },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
      ],
      excludeFromBody: ['id'],
    },
  ],
}

const TENANT_ID = 'default'
const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const domain = ref<BizDomain>('finance')
const tableKey = ref<BizTable>('expenses')
const rows = ref<Record<string, unknown>[]>([])
const authUsers = ref<Array<{ userId: string; username: string; nickname: string }>>([])

const showFormModal = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const formDraft = ref<Record<string, unknown>>({})
const editKey = ref<Record<string, unknown> | null>(null)

const showDeleteModal = ref(false)
const deleteTarget = ref<Record<string, unknown> | null>(null)

const tableDefs = computed(() => TABLE_DEFS[domain.value])
const currentTable = computed(
  () => tableDefs.value.find(t => t.key === tableKey.value) ?? tableDefs.value[0],
)

const userOptions = computed((): SelectOption[] =>
  authUsers.value.map(u => ({
    label: u.nickname?.trim() || u.username || u.userId,
    value: u.userId,
  })),
)

const userLabelMap = computed(() => {
  const map = new Map<string, string>()
  for (const u of authUsers.value) {
    map.set(u.userId, u.nickname?.trim() || u.username || u.userId)
  }
  return map
})

function rowKeyOf(row: Record<string, unknown>): string {
  const table = currentTable.value.key
  if (table === 'leave-balances') return `${row.userId}-${row.year}`
  if (table === 'attendance-months') return `${row.userId}-${row.yearMonth}`
  return String(row.id ?? '')
}

function formatUserCell(userId: unknown): string {
  if (userId == null || userId === '') return ''
  const id = String(userId)
  return userLabelMap.value.get(id) ?? id
}

const columns = computed((): DataTableColumns<Record<string, unknown>> => {
  const table = currentTable.value
  const cols: DataTableColumns<Record<string, unknown>> = table.fields.map(f => ({
    title: f.label,
    key: f.key,
    ellipsis: { tooltip: true },
    render(row) {
      const v = row[f.key]
      if (f.kind === 'user') return formatUserCell(v)
      if (v == null) return ''
      return String(v)
    },
  }))
  cols.push({
    title: '操作',
    key: '_actions',
    width: 140,
    render(row) {
      return h(
        NSpace,
        { size: 8 },
        {
          default: () => [
            h(
              NButton,
              { size: 'tiny', quaternary: true, onClick: () => openEdit(row) },
              { default: () => '编辑' },
            ),
            h(
              NButton,
              { size: 'tiny', quaternary: true, type: 'error', onClick: () => openDelete(row) },
              { default: () => '删除' },
            ),
          ],
        },
      )
    },
  })
  return cols
})

function emptyDraft(): Record<string, unknown> {
  const draft: Record<string, unknown> = {}
  for (const f of currentTable.value.fields) {
    if (f.key === 'tenantId') {
      draft.tenantId = TENANT_ID
    } else if (f.kind === 'number' || f.kind === 'year') {
      draft[f.key] = null
    } else {
      draft[f.key] = null
    }
  }
  return draft
}

function buildBody(draft: Record<string, unknown>): Record<string, unknown> {
  const exclude = new Set(currentTable.value.excludeFromBody ?? [])
  const body: Record<string, unknown> = {}
  for (const f of currentTable.value.fields) {
    if (exclude.has(f.key)) continue
    let v = draft[f.key]
    if (f.kind === 'number' || f.kind === 'year') {
      if (v === '' || v == null) {
        body[f.key] = null
      } else {
        body[f.key] = typeof v === 'number' ? v : Number(v)
      }
    } else {
      body[f.key] = typeof v === 'string' ? v.trim() : v
    }
  }
  return body
}

function compositeKeyChanged(body: Record<string, unknown>): boolean {
  if (!editKey.value) return false
  for (const f of currentTable.value.fields) {
    if (!f.compositeKey) continue
    const next = body[f.key]
    const prev = editKey.value[f.key]
    if (String(next ?? '') !== String(prev ?? '')) return true
  }
  return false
}

async function loadAuthUsers() {
  try {
    authUsers.value = await listAuthUsers(TENANT_ID)
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '加载用户列表失败')
    console.error(e)
    authUsers.value = []
  }
}

async function loadRows() {
  loading.value = true
  try {
    rows.value = await listBizRows(domain.value, currentTable.value.key, {
      tenantId: TENANT_ID,
    })
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '加载数据失败')
    console.error(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function refresh() {
  await Promise.all([loadAuthUsers(), loadRows()])
}

function selectTable(key: BizTable) {
  if (tableKey.value === key) return
  tableKey.value = key
}

function openCreate() {
  formMode.value = 'create'
  editKey.value = null
  formDraft.value = emptyDraft()
  showFormModal.value = true
}

function openEdit(row: Record<string, unknown>) {
  formMode.value = 'edit'
  editKey.value = { ...row }
  const draft = emptyDraft()
  for (const f of currentTable.value.fields) {
    const v = row[f.key]
    if (f.kind === 'year' || f.kind === 'number') {
      draft[f.key] = v == null || v === '' ? null : Number(v)
    } else {
      draft[f.key] = v == null ? null : String(v)
    }
  }
  formDraft.value = draft
  showFormModal.value = true
}

function openDelete(row: Record<string, unknown>) {
  deleteTarget.value = row
  showDeleteModal.value = true
}

function draftText(key: string): string | null {
  const v = formDraft.value[key]
  if (v == null || v === '') return null
  return String(v)
}

function draftNumber(key: string): number | null {
  const v = formDraft.value[key]
  return typeof v === 'number' && !Number.isNaN(v) ? v : null
}

function draftUser(key: string): string | null {
  const v = formDraft.value[key]
  return typeof v === 'string' && v ? v : null
}

/** year picker: Naive formatted-value 为 yyyy 字符串，草稿存 number */
function draftYearFormatted(key: string): string | null {
  const n = draftNumber(key)
  return n == null ? null : String(n)
}

function setDraft(key: string, value: unknown) {
  formDraft.value[key] = value ?? null
}

function setYearDraft(key: string, formatted: string | null) {
  if (!formatted) {
    formDraft.value[key] = null
    return
  }
  const n = Number(formatted)
  formDraft.value[key] = Number.isFinite(n) ? n : null
}

function selectOptionsFor(f: FieldDef): SelectOption[] {
  const base = f.options ? [...f.options] : []
  const current = formDraft.value[f.key]
  if (current != null && current !== '') {
    const val = String(current)
    if (!base.some(o => String(o.value) === val)) {
      base.unshift({ label: val, value: val })
    }
  }
  return base
}

async function submitForm() {
  const body = buildBody(formDraft.value)
  for (const f of currentTable.value.fields) {
    if (!f.required) continue
    const v = body[f.key]
    if (v == null || v === '') {
      message.warning(`请填写${f.label}`)
      return
    }
  }
  saving.value = true
  try {
    if (formMode.value === 'create') {
      await createBizRow(domain.value, currentTable.value.key, body)
      message.success('已创建')
    } else if (editKey.value) {
      const table = currentTable.value.key
      if (compositeKeyChanged(body)) {
        await createBizRow(domain.value, table, body)
        const query: Record<string, string> = {
          tenantId: String(editKey.value.tenantId ?? TENANT_ID),
        }
        await deleteBizRow(domain.value, table, editKey.value, query)
      } else {
        await updateBizRow(domain.value, table, editKey.value, body)
      }
      message.success('已保存')
    }
    showFormModal.value = false
    await loadRows()
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '保存失败')
    console.error(e)
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    const query: Record<string, string> = {}
    const table = currentTable.value.key
    if (table === 'leave-balances' || table === 'attendance-months') {
      query.tenantId = String(deleteTarget.value.tenantId ?? TENANT_ID)
    }
    await deleteBizRow(domain.value, table, deleteTarget.value, query)
    message.success('已删除')
    showDeleteModal.value = false
    deleteTarget.value = null
    await loadRows()
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '删除失败')
    console.error(e)
  } finally {
    deleting.value = false
  }
}

watch(domain, () => {
  const next = TABLE_DEFS[domain.value][0].key
  if (tableKey.value === next) {
    void loadRows()
  } else {
    tableKey.value = next
  }
})

watch(tableKey, () => {
  void loadRows()
})

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="mock-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>业务数据</h2>
      </div>
      <NSpace :size="8">
        <NButton round secondary :loading="loading" @click="refresh">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
        <NButton round type="primary" class="action-btn" @click="openCreate">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
      </NSpace>
    </header>

    <div class="domain-tabs">
      <NTabs v-model:value="domain" type="line" size="small" animated>
        <NTabPane v-for="d in DOMAINS" :key="d.key" :name="d.key" :tab="d.label" />
      </NTabs>
    </div>

    <div class="mock-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">数据表</span>
          <span class="panel-count">{{ tableDefs.length }}</span>
        </div>
        <div class="list-body">
          <button
            v-for="t in tableDefs"
            :key="t.key"
            type="button"
            class="table-card"
            :class="{ active: t.key === currentTable.key }"
            @click="selectTable(t.key)"
          >
            <span class="table-label">{{ t.label }}</span>
            <span class="table-id">{{ t.key }}</span>
          </button>
        </div>
      </aside>

      <section class="detail-panel">
        <div class="panel-head detail-head">
          <span class="panel-title">{{ currentTable.label }}</span>
          <span class="panel-count">{{ rows.length }}</span>
        </div>
        <NSpin :show="loading" size="small" class="detail-spin">
          <div class="detail-body">
            <NDataTable
              v-if="rows.length > 0"
              :columns="columns"
              :data="rows"
              :row-key="rowKeyOf"
              :bordered="false"
              size="small"
              class="biz-table"
            />
            <div v-else-if="!loading" class="detail-empty">
              <NEmpty description="暂无数据，点击右上角新建" />
            </div>
          </div>
        </NSpin>
      </section>
    </div>

    <NModal
      v-model:show="showFormModal"
      preset="dialog"
      :title="formMode === 'create' ? `新建 · ${currentTable.label}` : `编辑 · ${currentTable.label}`"
      class="sunshine-dialog"
      style="width: 520px"
    >
      <NForm class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem
          v-for="f in currentTable.fields"
          :key="f.key"
          :label="f.label"
          :required="f.required"
        >
          <NSelect
            v-if="f.kind === 'user'"
            :value="draftUser(f.key)"
            class="sun-field"
            filterable
            clearable
            :options="userOptions"
            :placeholder="`选择${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
          <NSelect
            v-else-if="f.kind === 'select'"
            :value="draftText(f.key)"
            class="sun-field"
            filterable
            tag
            clearable
            :options="selectOptionsFor(f)"
            :placeholder="`选择或输入${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
          <NDatePicker
            v-else-if="f.kind === 'date'"
            class="sun-field sun-field-grow"
            type="date"
            clearable
            :formatted-value="draftText(f.key)"
            value-format="yyyy-MM-dd"
            :placeholder="`选择${f.label}`"
            @update:formatted-value="(v) => setDraft(f.key, v)"
          />
          <NDatePicker
            v-else-if="f.kind === 'month'"
            class="sun-field sun-field-grow"
            type="month"
            clearable
            :formatted-value="draftText(f.key)"
            value-format="yyyy-MM"
            :placeholder="`选择${f.label}`"
            @update:formatted-value="(v) => setDraft(f.key, v)"
          />
          <NDatePicker
            v-else-if="f.kind === 'year'"
            class="sun-field sun-field-grow"
            type="year"
            clearable
            :formatted-value="draftYearFormatted(f.key)"
            value-format="yyyy"
            :placeholder="`选择${f.label}`"
            @update:formatted-value="(v) => setYearDraft(f.key, v)"
          />
          <NInputNumber
            v-else-if="f.kind === 'number'"
            :value="draftNumber(f.key)"
            class="sun-field sun-field-grow"
            clearable
            :show-button="false"
            :placeholder="`输入${f.label}`"
            @update:value="(v) => { formDraft[f.key] = v }"
          />
          <NInput
            v-else-if="f.kind === 'textarea'"
            :value="draftText(f.key) ?? ''"
            class="sun-field sun-field-grow"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            :placeholder="`输入${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
          <NInput
            v-else
            :value="draftText(f.key) ?? ''"
            class="sun-field"
            clearable
            :placeholder="`输入${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showFormModal = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="saving" @click="submitForm">
          {{ formMode === 'create' ? '创建' : '保存' }}
        </NButton>
      </template>
    </NModal>

    <NModal
      v-model:show="showDeleteModal"
      preset="dialog"
      title="删除确认"
      class="sunshine-dialog"
    >
      <p>确定删除该条「{{ currentTable.label }}」记录？此操作不可恢复。</p>
      <template #action>
        <NButton @click="showDeleteModal = false">取消</NButton>
        <NButton type="error" :loading="deleting" @click="confirmDelete">删除</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.mock-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
  min-height: 36px;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  line-height: 36px;
  color: var(--sun-text);
}

.domain-tabs {
  flex-shrink: 0;
  border-bottom: 1px solid var(--sun-border);
}

.domain-tabs :deep(.n-tabs-nav) {
  --n-tab-text-color: var(--sun-text-muted);
  --n-tab-text-color-active: var(--sun-text);
  --n-tab-text-color-hover: var(--sun-text);
  --n-bar-color: var(--sun-text);
  --n-pane-padding-top: 0;
}

.mock-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(240px, 280px) 1fr;
  gap: 16px;
}

.list-panel,
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-count {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.list-body {
  padding: 12px 14px 14px;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.table-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.table-card:hover {
  border-color: var(--sun-border-light);
}

.table-card.active {
  box-shadow: inset 0 0 0 1px var(--sun-text);
  border-color: var(--sun-text);
}

.table-label {
  font-size: 13px;
  font-weight: 600;
}

.table-card.active .table-label {
  font-weight: 700;
}

.table-id {
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, ui-monospace, monospace);
}

.detail-spin {
  flex: 1;
  min-height: 0;
}

.detail-spin :deep(.n-spin-content) {
  height: 100%;
}

.detail-body {
  padding: 12px 16px 16px;
  min-height: 0;
  height: 100%;
  overflow: auto;
  box-sizing: border-box;
}

.detail-empty {
  height: 100%;
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.biz-table :deep(.n-data-table) {
  --n-th-color: transparent;
  --n-td-color: transparent;
  --n-th-color-hover: transparent;
  --n-td-color-hover: transparent;
  --n-border-color: var(--sun-border);
  --n-th-text-color: var(--sun-text-muted);
  --n-td-text-color: var(--sun-text);
}

.biz-table :deep(.n-data-table-th),
.biz-table :deep(.n-data-table-td) {
  background: transparent !important;
}

.modal-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.action-btn {
  min-width: 96px;
}

.sun-field {
  width: 100%;
}

.sun-field-grow {
  width: 100%;
}

.sun-field :deep(.n-input),
.sun-field :deep(.n-input-wrapper),
.sun-field :deep(.n-base-selection),
.sun-field :deep(.n-input-number),
.sun-field :deep(.n-date-picker) {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  background: var(--sun-black) !important;
  width: 100%;
}

.sun-field :deep(.n-input__input-el),
.sun-field :deep(.n-input__textarea-el),
.sun-field :deep(.n-base-selection-input),
.sun-field :deep(.n-input-number-input),
.sun-field :deep(.n-input__border),
.sun-field :deep(.n-date-picker .n-input) {
  color: var(--sun-text) !important;
}

.sun-field :deep(.n-date-picker) {
  width: 100%;
}
</style>
