<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { NButton, NEmpty, NIcon, NProgress, NSelect, NTag, NText } from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import {
  applyConfigSuggestions,
  getEvalJob,
  getEvalReport,
  listEvalJobs,
  listKbConfigVersions,
  suggestEvalFix,
  type EvalJobStatus,
  type EvalJobSummary,
  type EvalReportView,
  type EvalSuggestResult,
  type EvalSuiteSummary,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import KbEvalResultView from './KbEvalResultView.vue'
import KbEvalReportDrawer from './KbEvalReportDrawer.vue'
import { useKbWorkbenchContext } from '../../composables/useKbWorkbenchContext'
import { useResizableSidePanel } from '../../composables/useResizableSidePanel'
import {
  EVAL_GATE_LABEL,
  EVAL_JOB_STATUS_LABEL,
  evalJobProgressPct,
  evalJobProgressText,
  formatEvalTime,
  jobMatchesAppliedConfig,
} from '../../utils/evalConstants'
import { collectSuggestionsForApply } from '../../utils/kbConfigPathLabel'
import { canShowEvalSuggestActions, evalJobConfigVersionDisplay } from '../../utils/kbConfigVersion'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
  suites: EvalSuiteSummary[]
  highlightJobId?: number | null
  /** 嵌入运行评测 Tab：隐藏「前往运行」等跳转 */
  embedded?: boolean
  /** 当前运行中任务的实时状态（用于列表进度列） */
  liveJob?: EvalJobStatus | null
}>()

const emit = defineEmits<{
  goRun: []
  runningForApplied: [busy: boolean]
}>()

const wb = useKbWorkbenchContext()
const loading = ref(false)
const refreshing = ref(false)
const historyLayoutRef = ref<HTMLElement | null>(null)
const { panelWidth, canResize, onResizePointerDown } = useResizableSidePanel(
  'sunshine-eval-report-drawer-width',
  historyLayoutRef,
  'right',
)
const error = ref('')
const jobs = ref<EvalJobSummary[]>([])
const selectedJobId = ref<number | null>(null)
const selectedConfigVersionId = ref<number | null>(null)
const report = ref<EvalReportView | null>(null)
const loadingReport = ref(false)
const suggestResult = ref<EvalSuggestResult | null>(null)
const suggesting = ref(false)
const applying = ref(false)
const info = ref('')

const historyScope = ref<'all' | 'current'>('all')
const historyStatus = ref<'all' | 'done' | 'failed' | 'running'>('all')
const historySuite = ref<string>('all')
/** 定时拉取的运行中任务详情（刷新页后仍可用） */
const polledJobs = ref(new Map<number, EvalJobStatus>())
let pollTimer: ReturnType<typeof setInterval> | null = null

const activeRunningJobs = computed(() =>
  jobs.value.filter((item) => item.status === 'running' || item.status === 'pending'),
)

const suiteFilterOptions = computed(() => [
  { label: '全部评测集', value: 'all' },
  ...props.suites.map((s) => ({ label: s.displayName, value: s.suiteKey })),
])

const suiteNameMap = computed(() => {
  const m = new Map<string, string>()
  for (const s of props.suites) m.set(s.suiteKey, s.displayName)
  return m
})

const filteredJobs = computed(() => {
  let list = jobs.value
  if (historyScope.value === 'current') {
    const versionId = wb.appliedConfig.value.versionId
    if (versionId != null) {
      list = list.filter((item) => item.configVersionId === versionId)
    }
  }
  if (historyStatus.value !== 'all') {
    list = list.filter((item) => item.status === historyStatus.value)
  }
  if (historySuite.value !== 'all') {
    list = list.filter((item) => item.suiteKey === historySuite.value)
  }
  return list
})

const runningForApplied = computed(() =>
  jobs.value.some((item) => jobMatchesAppliedConfig(item, wb.appliedConfig.value)),
)

watch(runningForApplied, (busy) => {
  emit('runningForApplied', busy)
}, { immediate: true })

function suiteLabel(key: string): string {
  return suiteNameMap.value.get(key) ?? key
}

function statusTag(item: EvalJobSummary): { label: string; type: 'success' | 'error' | 'warning' | 'info' } {
  if (item.status === 'running' || item.status === 'pending') {
    return { label: EVAL_JOB_STATUS_LABEL[item.status] ?? item.status, type: 'info' }
  }
  if (item.status === 'failed') {
    return { label: '失败', type: 'error' }
  }
  if (item.passedGate === true) return { label: EVAL_GATE_LABEL.pass, type: 'success' }
  if (item.passedGate === false) return { label: EVAL_GATE_LABEL.fail, type: 'warning' }
  return { label: EVAL_JOB_STATUS_LABEL.done ?? '已完成', type: 'info' }
}

function isJobRunning(item: EvalJobSummary): boolean {
  return item.status === 'running' || item.status === 'pending'
}

function resolvedJobStatus(item: EvalJobSummary): EvalJobStatus | null {
  if (props.liveJob?.jobId === item.jobId) return props.liveJob
  return polledJobs.value.get(item.jobId) ?? null
}

function showJobProgress(item: EvalJobSummary): boolean {
  const live = resolvedJobStatus(item)
  if (live) return live.status === 'running' || live.status === 'pending'
  return isJobRunning(item)
}

function jobProgressPct(item: EvalJobSummary): number {
  const live = resolvedJobStatus(item)
  if (live) return evalJobProgressPct(live)
  if (item.status === 'done') return 100
  if (item.status === 'failed') return 100
  return 0
}

function jobProgressLabel(item: EvalJobSummary): string {
  const live = resolvedJobStatus(item)
  if (live) {
    const pct = evalJobProgressPct(live)
    const detail = evalJobProgressText(live)
    return detail.includes('条') ? `${pct}% · ${detail}` : `${pct}%`
  }
  if (item.status === 'done') return '100%'
  if (isJobRunning(item)) return EVAL_JOB_STATUS_LABEL[item.status] ?? item.status
  return '—'
}

function configVersionDisplay(item: EvalJobSummary) {
  return evalJobConfigVersionDisplay(item, wb.configVersions.value)
}

function allowSuggestActions(item: EvalJobSummary | undefined): boolean {
  return canShowEvalSuggestActions(item, jobs.value, wb.configVersions.value)
}

function stopJobPolling() {
  if (pollTimer != null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollRunningJobs() {
  if (!props.kbId) return
  const running = activeRunningJobs.value
  if (running.length === 0) {
    stopJobPolling()
    return
  }
  let needReload = false
  const next = new Map(polledJobs.value)
  for (const item of running) {
    try {
      const status = await getEvalJob(props.tenantId, item.jobId)
      next.set(item.jobId, status)
      if (status.status === 'done' || status.status === 'failed') {
        needReload = true
      }
    } catch {
      // 单次拉取失败忽略，下一轮重试
    }
  }
  polledJobs.value = next
  if (running.length > 0) {
    try {
      const versionList = await listKbConfigVersions(props.tenantId, props.kbId)
      wb.setConfigVersions(versionList)
    } catch {
      // 轮询刷新失败忽略
    }
  }
  if (needReload) {
    polledJobs.value = new Map()
    await loadJobs()
    if (selectedJobId.value != null) {
      const selected = jobs.value.find((j) => j.jobId === selectedJobId.value)
      if (selected && (selected.status === 'done' || selected.status === 'failed')) {
        await selectJob(selectedJobId.value)
      }
    }
  }
}

function startJobPolling() {
  if (pollTimer != null) return
  void pollRunningJobs()
  pollTimer = setInterval(() => { void pollRunningJobs() }, 1200)
}

watch(activeRunningJobs, (list) => {
  if (list.length > 0) startJobPolling()
  else stopJobPolling()
})

async function loadJobs() {
  if (!props.kbId) {
    jobs.value = []
    polledJobs.value = new Map()
    stopJobPolling()
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [jobList, versionList] = await Promise.all([
      listEvalJobs(props.tenantId, props.kbId, 50),
      listKbConfigVersions(props.tenantId, props.kbId),
    ])
    jobs.value = jobList
    wb.setConfigVersions(versionList)
    if (activeRunningJobs.value.length > 0) startJobPolling()
    else stopJobPolling()
  } catch (e) {
    error.value = friendlyErrorMessage(e, '加载记录失败')
  } finally {
    loading.value = false
  }
}

async function selectJob(jobId: number) {
  if (selectedJobId.value === jobId && report.value) {
    closeReport()
    return
  }
  selectedJobId.value = jobId
  selectedConfigVersionId.value = null
  report.value = null
  suggestResult.value = null
  info.value = ''
  loadingReport.value = true
  try {
    const job = await getEvalJob(props.tenantId, jobId)
    selectedConfigVersionId.value = job.configVersionId
    if (job.reportId != null) {
      report.value = await getEvalReport(props.tenantId, job.reportId)
      suggestResult.value = report.value.suggestions ?? null
      const summary = jobs.value.find((j) => j.jobId === jobId)
      if (
        report.value.passedGate === false
        && !suggestResult.value
        && summary
        && allowSuggestActions(summary)
      ) {
        void handleSuggest()
      }
    }
  } catch (e) {
    error.value = friendlyErrorMessage(e, '加载报告失败')
  } finally {
    loadingReport.value = false
  }
}

function closeReport() {
  selectedJobId.value = null
  selectedConfigVersionId.value = null
  report.value = null
  suggestResult.value = null
  info.value = ''
  loadingReport.value = false
}

async function handleSuggest() {
  if (!props.kbId || !report.value) return
  suggesting.value = true
  info.value = ''
  try {
    suggestResult.value = await suggestEvalFix(props.tenantId, {
      reportId: report.value.reportId,
      kbId: props.kbId,
    })
  } catch (e) {
    error.value = friendlyErrorMessage(e, '生成建议失败')
  } finally {
    suggesting.value = false
  }
}

async function handleApplySuggestions() {
  if (!props.kbId || !suggestResult.value) return
  const items = collectSuggestionsForApply(suggestResult.value)
  if (!items.length) return
  applying.value = true
  info.value = ''
  try {
    const versionId = selectedConfigVersionId.value
    if (versionId == null) {
      error.value = '未关联评测失败配置版本，无法应用建议'
      return
    }
    await applyConfigSuggestions(props.tenantId, props.kbId, items, versionId)
    const versionList = await listKbConfigVersions(props.tenantId, props.kbId)
    wb.setConfigVersions(versionList)
    info.value = `已将 ${items.length} 条参数建议写入草稿（原评测失败版本 #${versionId}），可在「参数配置」Tab 编辑后重新提交评测`
    wb.bumpRevision()
  } catch (e) {
    error.value = friendlyErrorMessage(e, '应用失败')
  } finally {
    applying.value = false
  }
}

async function reload() {
  await loadJobs()
  if (props.highlightJobId != null) {
    await selectJob(props.highlightJobId)
  }
}

async function handleRefresh() {
  refreshing.value = true
  try {
    await reload()
  } finally {
    refreshing.value = false
  }
}

watch(
  () => [props.tenantId, props.kbId, props.highlightJobId] as const,
  () => { void reload() },
)

onMounted(() => { void reload() })

onUnmounted(() => {
  stopJobPolling()
})

defineExpose({ reload, selectJob, runningForApplied })
</script>

<template>
  <div class="history-tab" :class="{ embedded, 'has-drawer': selectedJobId != null }">
    <div ref="historyLayoutRef" class="history-layout">
      <div class="history-content">
        <div class="filter-bar">
      <NSelect
        v-model:value="historyScope"
        size="small"
        :options="[
          { label: '全部配置', value: 'all' },
          { label: '仅当前应用配置', value: 'current' },
        ]"
        class="filter-select filter-scope"
        :menu-props="{ class: 'eval-select-menu' }"
      />
      <NSelect
        v-model:value="historyStatus"
        size="small"
        :options="[
          { label: '全部状态', value: 'all' },
          { label: '已完成', value: 'done' },
          { label: '失败', value: 'failed' },
          { label: '运行中', value: 'running' },
        ]"
        class="filter-select filter-status"
        :menu-props="{ class: 'eval-select-menu' }"
      />
      <NSelect
        v-model:value="historySuite"
        size="small"
        :options="suiteFilterOptions"
        class="filter-select filter-suite"
        :menu-props="{ class: 'eval-select-menu' }"
      />
      <NButton
        size="small"
        round
        secondary
        class="refresh-btn"
        :loading="refreshing || loading"
        @click="handleRefresh"
      >
        <template #icon><NIcon :component="RefreshOutline" /></template>
        刷新
      </NButton>
      <NText depth="3" class="filter-count">共 {{ filteredJobs.length }} 条</NText>
        </div>
        <NText v-if="error" type="error">{{ error }}</NText>
        <NText v-if="info" type="success">{{ info }}</NText>
        <div v-if="filteredJobs.length > 0" class="history-table" :class="{ 'history-table--embedded': embedded }">
      <div class="history-head">
        <span>任务</span>
        <span>评测集</span>
        <span>配置版本</span>
        <span>状态</span>
        <span>进度</span>
        <span>R@5</span>
        <span>时间</span>
      </div>
      <button
        v-for="item in filteredJobs"
        :key="item.jobId"
        type="button"
        :class="['history-row', { active: selectedJobId === item.jobId, running: isJobRunning(item) }]"
        @click="selectJob(item.jobId)"
      >
        <span>#{{ item.jobId }}</span>
        <span class="ellipsis">{{ suiteLabel(item.suiteKey) }}</span>
        <span class="version-cell">
          <NTag
            size="small"
            :bordered="false"
            round
            :type="configVersionDisplay(item).tagType"
          >
            {{ configVersionDisplay(item).statusLabel }}
          </NTag>
          <span class="version-time" :title="configVersionDisplay(item).timeLabel">
            {{ configVersionDisplay(item).timeLabel }}
          </span>
        </span>
        <span>
          <NTag size="tiny" :bordered="false" round :type="statusTag(item).type">
            {{ statusTag(item).label }}
          </NTag>
        </span>
        <span class="progress-cell">
          <NProgress
            v-if="showJobProgress(item)"
            type="line"
            :percentage="jobProgressPct(item)"
            :show-indicator="false"
            :height="6"
            :processing="showJobProgress(item)"
            class="progress-bar"
          />
          <span class="progress-label">{{ jobProgressLabel(item) }}</span>
        </span>
        <span :class="{ 'score-bad': item.recallAt5 != null && item.recallAt5 < 0.9 }">
          {{ item.recallAt5 != null ? item.recallAt5.toFixed(4) : '—' }}
        </span>
        <span class="time-cell">{{ formatEvalTime(item.createdAt) }}</span>
      </button>
        </div>
        <div v-else-if="!loading" class="empty-wrap">
          <NEmpty size="small" description="暂无评测记录" />
          <NButton v-if="!embedded" size="small" round type="primary" class="action-btn" @click="emit('goRun')">前往运行评测</NButton>
        </div>
      </div>
      <KbEvalReportDrawer
        v-if="selectedJobId != null"
        :title="`报告详情 · 任务 #${selectedJobId}`"
        :loading="loadingReport"
        :width="panelWidth"
        :can-resize="canResize"
        @close="closeReport"
        @resize-pointer-down="onResizePointerDown"
      >
        <KbEvalResultView
          v-if="report"
          :report="report"
          :job-id="selectedJobId"
          :config-version-id="selectedConfigVersionId"
          :allow-suggest-actions="allowSuggestActions(jobs.find((j) => j.jobId === selectedJobId))"
          :suite-display-name="suiteLabel(jobs.find((j) => j.jobId === selectedJobId)?.suiteKey ?? '')"
          :suggest-result="suggestResult"
          :suggesting="suggesting"
          :applying="applying"
          @suggest="handleSuggest"
          @apply-suggestions="handleApplySuggestions"
        />
      </KbEvalReportDrawer>
    </div>
  </div>
</template>

<style scoped>
.history-tab {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.history-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
}
.history-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding-right: 4px;
}
.history-tab:not(.embedded) .history-content {
  padding-top: 0;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.filter-select {
  flex-shrink: 0;
}
.filter-scope {
  width: 200px;
}
.filter-status {
  width: 160px;
}
.filter-suite {
  width: 280px;
}
.refresh-btn {
  flex-shrink: 0;
}
.filter-count {
  margin-left: auto;
  font-size: 13px;
  white-space: nowrap;
}
.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}
.history-table {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.history-tab.embedded {
  overflow: hidden;
}
.history-tab.embedded .history-layout {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.history-tab.embedded .history-content {
  overflow: hidden;
}
.history-tab.embedded .filter-bar {
  flex-shrink: 0;
  padding: 8px 8px 0;
}
.history-tab.embedded .history-table--embedded {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}
.history-head,
.history-row {
  display: grid;
  grid-template-columns: 64px 1.1fr minmax(200px, 1.6fr) 88px minmax(120px, 1.4fr) 72px 132px;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  text-align: left;
}
.version-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.version-cell :deep(.n-tag) {
  flex-shrink: 0;
}
.version-time {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--sun-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.progress-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.progress-bar {
  width: 100%;
}
.progress-label {
  font-size: 11px;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.history-row.running {
  border-color: var(--sun-border-light);
}
.history-head {
  padding: 6px 10px;
  color: var(--sun-text-muted);
  font-size: 12px;
  border-bottom: 1px solid var(--sun-border);
}
.history-row {
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  background: var(--sun-black);
  color: var(--sun-text);
  cursor: pointer;
}
.history-row.active {
  box-shadow: inset 0 0 0 1px var(--sun-border-light);
  border-color: var(--sun-border-light);
}
.history-row:hover {
  border-color: var(--sun-border-light);
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.score-bad {
  color: var(--n-error-color);
}
.time-cell {
  font-size: 12px;
  color: var(--sun-text-muted);
}
.empty-wrap {
  flex: 1;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 32px 24px;
  min-height: 160px;
}
.filter-select :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-box-shadow-focus: none !important;
}
@media (max-width: 800px) {
  .history-head {
    display: none;
  }
  .history-row {
    grid-template-columns: 1fr 1fr;
    gap: 4px;
  }
}
</style>
