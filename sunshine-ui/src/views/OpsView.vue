<script setup lang="ts">
import {
  NButton,
  NDataTable,
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
  NSwitch,
  NTabPane,
  NTabs,
  useMessage,
  type DataTableColumns,
} from 'naive-ui'
import { RefreshOutline, AddOutline } from '@vicons/ionicons5'
import { computed, h, onMounted, ref } from 'vue'
import SidebarToggle from '../components/SidebarToggle.vue'
import TenantSelector from '../components/knowledge/TenantSelector.vue'
import {
  listUsageDaily,
  listUsageSummary,
  listTenantQuotas,
  upsertTenantQuota,
  deleteTenantQuota,
  type UsageDailyRow,
  type UsageSummaryRow,
  type TenantQuota,
} from '../api/usage'
import { listModelDefinitions, type ModelDefinition } from '../api/models'
import { TENANT_OPTIONS, type TenantOption } from '../api/tenants'

const message = useMessage()

const loading = ref(false)
const quotaLoading = ref(false)
const activeTab = ref('usage')
const tenantFilter = ref<string>('')
const summaryRows = ref<UsageSummaryRow[]>([])
const dailyRows = ref<UsageDailyRow[]>([])
const quotas = ref<TenantQuota[]>([])
const modelDefinitions = ref<ModelDefinition[]>([])

function dayStartDaysAgo(days: number): number {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() - days)
  return d.getTime()
}

/** 租户下拉：预置租户 ∪ 配额中已存在的租户（去重） */
const tenantOptions = computed<TenantOption[]>(() => {
  const seen = new Set<string>()
  const opts: TenantOption[] = []
  for (const t of TENANT_OPTIONS) {
    opts.push(t)
    seen.add(t.value)
  }
  for (const q of quotas.value) {
    if (q.tenantId && !seen.has(q.tenantId)) {
      opts.push({ value: q.tenantId, label: q.tenantId, description: '已配置租户' })
      seen.add(q.tenantId)
    }
  }
  return opts
})

/** 模型白名单下拉选项（已注册模型定义） */
const modelOptions = computed(() =>
  modelDefinitions.value.map((d) => ({ label: d.displayName || d.modelName, value: d.modelName })),
)

function onTenantFilterChange(value: string) {
  tenantFilter.value = value
  void refreshUsage()
}

const totals = computed(() => {
  let calls = 0
  let tokens = 0
  let cost = 0
  for (const row of summaryRows.value) {
    calls += row.calls
    tokens += row.totalTokens
    cost += Number(row.estCost || 0)
  }
  return { calls, tokens, cost }
})

function fmtTokens(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(2)}M`
  if (v >= 1_000) return `${(v / 1_000).toFixed(1)}k`
  return String(v)
}

function fmtCost(v: number): string {
  if (v === 0) return '—'
  return `¥${v.toFixed(4)}`
}

function fmtShortDate(statDate: string): string {
  // 后端返回 YYYY-MM-DD
  const parts = statDate.split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : statDate
}

const maxDailyTokens = computed(() =>
  dailyTrend.value.reduce((max, r) => Math.max(max, r.totalTokens), 0),
)

/** 柱状图数据：daily 接口粒度 = 日×模型×调用点，按 statDate 汇总成一天一根柱 */
const dailyTrend = computed(() => {
  const byDate = new Map<string, { statDate: string; totalTokens: number; calls: number }>()
  for (const r of dailyRows.value) {
    const cur = byDate.get(r.statDate) ?? { statDate: r.statDate, totalTokens: 0, calls: 0 }
    cur.totalTokens += r.totalTokens
    cur.calls += r.calls
    byDate.set(r.statDate, cur)
  }
  return [...byDate.values()].sort((a, b) => a.statDate.localeCompare(b.statDate))
})

const summaryColumns: DataTableColumns<UsageSummaryRow> = [
  { title: '模型', key: 'model', minWidth: 200 },
  { title: '调用次数', key: 'calls', width: 110, align: 'right' },
  { title: 'Prompt Tokens', key: 'promptTokens', width: 150, align: 'right', render: (r) => fmtTokens(r.promptTokens) },
  { title: 'Completion Tokens', key: 'completionTokens', width: 160, align: 'right', render: (r) => fmtTokens(r.completionTokens) },
  { title: 'Total Tokens', key: 'totalTokens', width: 150, align: 'right', render: (r) => fmtTokens(r.totalTokens) },
  { title: '估算成本', key: 'estCost', width: 120, align: 'right', render: (r) => fmtCost(Number(r.estCost || 0)) },
]

const dailyColumns: DataTableColumns<UsageDailyRow> = [
  { title: '日期', key: 'statDate', width: 110 },
  { title: '模型', key: 'model', minWidth: 180 },
  { title: '调用点', key: 'callSite', width: 110, render: (r) => r.callSite || '—' },
  { title: '调用次数', key: 'calls', width: 100, align: 'right' },
  { title: 'Prompt Tokens', key: 'promptTokens', width: 140, align: 'right', render: (r) => fmtTokens(r.promptTokens) },
  { title: 'Completion Tokens', key: 'completionTokens', width: 150, align: 'right', render: (r) => fmtTokens(r.completionTokens) },
  { title: 'Total Tokens', key: 'totalTokens', width: 140, align: 'right', render: (r) => fmtTokens(r.totalTokens) },
  { title: '估算成本', key: 'estCost', width: 120, align: 'right', render: (r) => fmtCost(Number(r.estCost || 0)) },
]

const quotaColumns: DataTableColumns<TenantQuota> = [
  { title: '租户', key: 'tenantId', width: 180 },
  {
    title: '月度 Token 上限',
    key: 'monthTokenLimit',
    width: 160,
    align: 'right',
    render: (r) => (r.monthTokenLimit > 0 ? fmtTokens(r.monthTokenLimit) : '不限'),
  },
  {
    title: '模型白名单',
    key: 'modelWhitelist',
    minWidth: 240,
    render: (r) => r.modelWhitelist || '不限制',
  },
  {
    title: '启用',
    key: 'enabled',
    width: 80,
    render: (r) => (r.enabled ? '是' : '否'),
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    render: (r) =>
      h(NSpace, { size: 10 }, [
        h(
          NButton,
          { size: 'small', quaternary: true, onClick: () => openQuotaEdit(r) },
          { default: () => '编辑' },
        ),
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'error', onClick: () => removeQuota(r.tenantId) },
          { default: () => '删除' },
        ),
      ]),
  },
]

async function refreshUsage() {
  loading.value = true
  try {
    const tenantId = tenantFilter.value || undefined
    const since = dayStartDaysAgo(6)
    summaryRows.value = await listUsageSummary({ since, tenantId })
    dailyRows.value = await listUsageDaily({ since, tenantId })
  } finally {
    loading.value = false
  }
}

async function refreshQuotas() {
  quotaLoading.value = true
  try {
    quotas.value = await listTenantQuotas()
  } finally {
    quotaLoading.value = false
  }
}

async function refreshModels() {
  try {
    modelDefinitions.value = await listModelDefinitions()
  } catch {
    modelDefinitions.value = []
  }
}

function refreshPage() {
  if (activeTab.value === 'usage') void refreshUsage()
  else void refreshQuotas()
}

// —— 配额编辑 ——
const showQuotaModal = ref(false)
const quotaDraft = ref<TenantQuota>({
  id: 0,
  tenantId: '',
  monthTokenLimit: 0,
  modelWhitelist: null,
  enabled: true,
  remark: null,
})
const quotaModelWhitelist = ref<string[]>([])

function parseWhitelist(raw: string | null | undefined): string[] {
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr.map(String) : []
  } catch {
    return []
  }
}

function openQuotaEdit(row?: TenantQuota) {
  quotaModelWhitelist.value = row ? parseWhitelist(row.modelWhitelist) : []
  quotaDraft.value = row
    ? { ...row }
    : { id: 0, tenantId: '', monthTokenLimit: 0, modelWhitelist: null, enabled: true, remark: null }
  showQuotaModal.value = true
}

async function submitQuota() {
  const draft = quotaDraft.value
  if (!draft.tenantId.trim()) {
    message.warning('租户 ID 不能为空')
    return
  }
  await upsertTenantQuota({
    tenantId: draft.tenantId.trim(),
    monthTokenLimit: draft.monthTokenLimit,
    modelWhitelist: quotaModelWhitelist.value.length ? JSON.stringify(quotaModelWhitelist.value) : null,
    enabled: draft.enabled,
    remark: draft.remark,
  })
  message.success('配额已保存')
  showQuotaModal.value = false
  await refreshQuotas()
}

async function removeQuota(tenantId: string) {
  await deleteTenantQuota(tenantId)
  message.success('配额已删除')
  await refreshQuotas()
}

onMounted(() => {
  void refreshUsage()
  void refreshQuotas()
  void refreshModels()
})
</script>

<template>
  <div class="ops-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>运营用量</h2>
      </div>
      <NSpace :size="8" align="center">
        <template v-if="activeTab === 'usage'">
          <TenantSelector
            :model-value="tenantFilter"
            include-all
            :options="tenantOptions"
            @update:model-value="onTenantFilterChange"
          />
        </template>
        <NButton v-if="activeTab === 'quota'" round secondary @click="openQuotaEdit()">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建配额
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="loading" @click="refreshPage">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <NTabs v-model:value="activeTab" type="line" :animated="false" class="ops-tabs">
      <NTabPane name="usage" tab="用量" />
      <NTabPane name="quota" tab="租户配额" />
    </NTabs>

    <div class="ops-panel">
      <template v-if="activeTab === 'usage'">
        <NSpin :show="loading" class="panel-spin">
          <div class="usage-stats">
            <div class="stat-card">
              <div class="stat-label">总调用次数</div>
              <div class="stat-value">{{ totals.calls }}</div>
            </div>
            <div class="stat-card">
              <div class="stat-label">Total Tokens</div>
              <div class="stat-value">{{ fmtTokens(totals.tokens) }}</div>
            </div>
            <div class="stat-card">
              <div class="stat-label">估算成本</div>
              <div class="stat-value">{{ fmtCost(totals.cost) }}</div>
            </div>
          </div>

          <div class="usage-section">
            <h3>模型用量排行</h3>
            <NDataTable
              v-if="summaryRows.length"
              :columns="summaryColumns"
              :data="summaryRows"
              :bordered="false"
              size="small"
              :row-key="(r) => r.model"
              :scroll-x="890"
              class="ops-table"
            />
            <div v-else class="panel-empty"><NEmpty description="暂无用量数据" /></div>
          </div>

          <div class="usage-section">
            <h3>日用量趋势（近 7 天）</h3>
            <div v-if="dailyTrend.length" class="trend-chart">
              <div
                v-for="d in dailyTrend"
                :key="d.statDate"
                class="trend-col"
                :title="`${d.statDate} · ${fmtTokens(d.totalTokens)}`"
              >
                <div class="trend-value">{{ fmtTokens(d.totalTokens) }}</div>
                <div class="trend-bar-wrap">
                  <div
                    class="trend-bar"
                    :style="{ height: maxDailyTokens > 0 ? `${Math.max(2, (d.totalTokens / maxDailyTokens) * 100)}%` : '2%' }"
                  />
                </div>
                <div class="trend-label">{{ fmtShortDate(d.statDate) }}</div>
              </div>
            </div>
            <div v-else class="panel-empty"><NEmpty description="暂无日聚合数据" /></div>
          </div>

          <div class="usage-section">
            <h3>日用量明细</h3>
            <NDataTable
              v-if="dailyRows.length"
              :columns="dailyColumns"
              :data="dailyRows"
              :bordered="false"
              size="small"
              :row-key="(r) => `${r.statDate}-${r.model}-${r.callSite ?? ''}`"
              :scroll-x="860"
              class="ops-table"
            />
            <div v-else class="panel-empty"><NEmpty description="暂无日明细" /></div>
          </div>
        </NSpin>
      </template>

      <template v-else>
        <NSpin :show="quotaLoading" class="panel-spin">
          <NDataTable
            v-if="quotas.length"
            :columns="quotaColumns"
            :data="quotas"
            :bordered="false"
            size="small"
            :row-key="(r) => r.id"
            :scroll-x="900"
            class="ops-table"
          />
          <div v-else class="panel-empty"><NEmpty description="暂无配额配置" /></div>
        </NSpin>
      </template>
    </div>

    <NModal
      v-model:show="showQuotaModal"
      preset="card"
      title="租户配额"
      class="sun-modal"
      style="width: 480px"
    >
      <NForm label-placement="top">
        <NFormItem label="租户">
          <TenantSelector
            :model-value="quotaDraft.tenantId"
            :options="tenantOptions"
            variant="block"
            @update:model-value="(v) => (quotaDraft.tenantId = v)"
          />
        </NFormItem>
        <NFormItem label="月度 Token 上限（0=不限）">
          <NInputNumber v-model:value="quotaDraft.monthTokenLimit" :min="0" class="sun-field" style="width: 100%" />
        </NFormItem>
        <NFormItem label="模型白名单（留空=不限制）">
          <NSelect
            v-model:value="quotaModelWhitelist"
            class="sun-field"
            multiple
            filterable
            clearable
            placeholder="选择允许的模型"
            :options="modelOptions"
            :menu-props="{ class: 'ops-select-menu' }"
          />
        </NFormItem>
        <NFormItem label="启用">
          <NSwitch v-model:value="quotaDraft.enabled" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="quotaDraft.remark" class="sun-field" placeholder="备注" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="showQuotaModal = false">取消</NButton>
          <NButton type="primary" @click="submitQuota">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.ops-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--sun-black);
  color: var(--sun-fg);
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-header h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.ops-tabs {
  padding: 0 16px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.ops-panel {
  flex: 1;
  overflow: auto;
  padding: 16px;
}

.panel-spin {
  min-height: 200px;
}

.usage-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  padding: 12px 16px;
  background: transparent;
}

.stat-label {
  font-size: 12px;
  color: var(--sun-fg-muted, #8b949e);
  margin-bottom: 6px;
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
}

.usage-section {
  margin-bottom: 20px;
}

.usage-section h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-fg);
}

.ops-table {
  --n-th-color: var(--sun-black) !important;
  --n-td-color: var(--sun-black) !important;
  --n-th-color-hover: var(--sun-black) !important;
  --n-td-color-hover: var(--sun-row-hover) !important;
  --n-border-color: var(--sun-border) !important;
}

.ops-table :deep(.n-data-table-th) {
  height: 44px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  color: var(--sun-text-secondary) !important;
  white-space: nowrap;
}

.ops-table :deep(.n-data-table-td) {
  height: 46px;
  font-size: var(--sun-font-base, 14px);
  padding: 10px 12px;
}

.ops-table :deep(.n-data-table-td .n-button) {
  font-size: var(--sun-font-sm, 12px);
}

.panel-empty {
  display: flex;
  justify-content: center;
  padding: 40px 0;
}

/* 日用量趋势柱状图 */
.trend-chart {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 16px;
  padding: 16px 12px 8px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  min-height: 180px;
}

.trend-col {
  flex: 1;
  max-width: 72px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.trend-value {
  font-size: 12px;
  color: var(--sun-fg-muted, #8b949e);
  white-space: nowrap;
}

.trend-bar-wrap {
  width: 100%;
  max-width: 44px;
  height: 110px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  border-bottom: 1px solid var(--sun-border);
}

.trend-bar {
  width: 100%;
  min-height: 2px;
  border-radius: 4px 4px 0 0;
  background: linear-gradient(180deg, var(--sun-accent, #3b82f6), var(--sun-accent-hover, #2563eb));
  opacity: 0.85;
  transition: height 0.2s ease;
}

.trend-label {
  font-size: 12px;
  color: var(--sun-fg-muted, #8b949e);
  white-space: nowrap;
}
</style>

<style>
.ops-select-menu {
  background: var(--sun-black) !important;
}
</style>
