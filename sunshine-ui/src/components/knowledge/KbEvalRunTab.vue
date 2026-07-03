<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  NButton,
  NSelect,
  NText,
  useMessage,
} from 'naive-ui'
import {
  getEvalJob,
  listKbConfigVersions,
  runEval,
  type EvalJobStatus,
  type EvalSuiteSummary,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import KbEvalHistoryTab from './KbEvalHistoryTab.vue'
import { useKbWorkbenchContext, appliedConfigToApi } from '../../composables/useKbWorkbenchContext'
import { canRunEvalForAppliedVersion, isBenchmarkEvalOnly } from '../../utils/kbConfigVersion'
import {
  DEFAULT_EVAL_SUITE_KEY,
  EVAL_STRATEGY_OPTIONS,
  evalSuiteOptionLabel,
} from '../../utils/evalConstants'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
  suites: EvalSuiteSummary[]
  suiteKey: string
}>()

const emit = defineEmits<{
  'update:suiteKey': [value: string]
}>()

const wb = useKbWorkbenchContext()
const message = useMessage()
const strategy = ref('')
const running = ref(false)
const remoteConfigBusy = ref(false)
const error = ref('')
const liveJob = ref<EvalJobStatus | null>(null)
const highlightJobId = ref<number | null>(null)
const historyRef = ref<InstanceType<typeof KbEvalHistoryTab> | null>(null)

const suiteOptions = computed(() =>
  props.suites.map((s) => ({
    label: evalSuiteOptionLabel(s.displayName, s.itemCount),
    value: s.suiteKey,
  })),
)

const appliedVersion = computed(() =>
  wb.configVersions.value.find((v) => v.id === wb.appliedConfig.value.versionId) ?? null,
)

const canRunEval = computed(() => canRunEvalForAppliedVersion(appliedVersion.value))
const isBenchmarkMode = computed(() => isBenchmarkEvalOnly(appliedVersion.value))
const runButtonLoading = computed(() => running.value || remoteConfigBusy.value)
const selectedSuiteName = computed(
  () => props.suites.find((s) => s.suiteKey === props.suiteKey)?.displayName ?? props.suiteKey,
)

async function refreshConfigVersions() {
  if (!props.kbId) return
  const list = await listKbConfigVersions(props.tenantId, props.kbId)
  wb.setConfigVersions(list)
}

async function pollJob(jobId: number, signal: AbortSignal): Promise<void> {
  for (let i = 0; i < 180; i++) {
    if (signal.aborted) return
    liveJob.value = await getEvalJob(props.tenantId, jobId)
    if (signal.aborted) return
    if (i % 3 === 0) {
      try {
        await refreshConfigVersions()
      } catch {
        // 轮询刷新失败忽略
      }
    }
    if (liveJob.value.status === 'done' || liveJob.value.status === 'failed') return
    await new Promise((r) => setTimeout(r, 1200))
  }
  throw new Error('评测超时')
}

async function handleRun() {
  if (!props.kbId || !canRunEval.value) return
  if (remoteConfigBusy.value && !running.value) {
    message.warning('当前配置正在评测中，请稍后')
    return
  }
  const controller = new AbortController()
  const benchmarkOnly = isBenchmarkMode.value
  running.value = true
  error.value = ''
  liveJob.value = null
  try {
    const cfg = appliedConfigToApi(wb.appliedConfig.value)
    const submitted = await runEval(props.tenantId, {
      suiteKey: props.suiteKey || DEFAULT_EVAL_SUITE_KEY,
      kbId: props.kbId,
      strategy: strategy.value || undefined,
      configMode: cfg.configMode,
      configVersionId: cfg.configVersionId,
    })
    liveJob.value = submitted
    highlightJobId.value = submitted.jobId
    if (!benchmarkOnly) {
      await refreshConfigVersions()
    }
    await historyRef.value?.reload()
    historyRef.value?.selectJob(submitted.jobId)
    await pollJob(submitted.jobId, controller.signal)
    await historyRef.value?.reload()
    historyRef.value?.selectJob(submitted.jobId)
    if (!benchmarkOnly) {
      await refreshConfigVersions()
      wb.bumpRevision()
    }
  } catch (e) {
    error.value = friendlyErrorMessage(e, '评测失败')
    await historyRef.value?.reload()
    if (!benchmarkOnly) wb.bumpRevision()
  } finally {
    running.value = false
    liveJob.value = null
    highlightJobId.value = null
  }
}

function reset() {
  strategy.value = ''
  running.value = false
  remoteConfigBusy.value = false
  error.value = ''
  liveJob.value = null
  highlightJobId.value = null
}

async function reloadHistory() {
  await historyRef.value?.reload()
}

defineExpose({ reset, reloadHistory })
</script>

<template>
  <div class="run-tab">
    <div class="run-toolbar">
      <span class="field-label">评测集</span>
      <NSelect
        :value="suiteKey"
        size="small"
        class="suite-select"
        :options="suiteOptions.length ? suiteOptions : [{ label: selectedSuiteName, value: suiteKey }]"
        :disabled="running"
        placeholder="选择评测集"
        :menu-props="{ class: 'eval-select-menu' }"
        @update:value="emit('update:suiteKey', $event)"
      />
      <NSelect
        v-model:value="strategy"
        size="small"
        class="strategy-select"
        :options="EVAL_STRATEGY_OPTIONS"
        :disabled="running"
        placeholder="策略"
        :menu-props="{ class: 'eval-select-menu' }"
      />
      <NButton
        type="primary"
        class="action-btn"
        round
        :loading="runButtonLoading"
        :disabled="!kbId || !canRunEval"
        @click="handleRun"
      >
        运行评测
      </NButton>
    </div>
    <NText v-if="error" type="error" class="msg-text">{{ error }}</NText>
    <KbEvalHistoryTab
      ref="historyRef"
      embedded
      :tenant-id="tenantId"
      :kb-id="kbId"
      :suites="suites"
      :highlight-job-id="highlightJobId"
      :live-job="liveJob"
      @running-for-applied="remoteConfigBusy = $event"
    />
  </div>
</template>

<style scoped>
.run-tab {
  height: 100%;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.run-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.field-label {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}
.suite-select {
  width: 280px;
}
.strategy-select {
  width: 160px;
}
.action-btn {
  margin-left: auto;
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-color-disabled: var(--sun-border) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-border: none !important;
  --n-border-disabled: none !important;
}
.msg-text {
  font-size: 13px;
  flex-shrink: 0;
}
.run-tab :deep(.history-tab.embedded) {
  flex: 1;
  min-height: 0;
}
.suite-select :deep(.n-base-selection),
.strategy-select :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-active: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}
</style>

<style>
.eval-select-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
  --n-option-color-active: transparent !important;
  --n-option-color-active-pending: var(--sun-row-hover) !important;
  --n-option-color-pending: var(--sun-row-hover) !important;
  --n-option-text-color: var(--sun-text) !important;
  --n-option-text-color-active: var(--sun-text) !important;
  --n-option-check-color: var(--sun-text) !important;
  background: var(--sun-black) !important;
  border: 1px solid var(--sun-border) !important;
  box-shadow: var(--shadow-elevated) !important;
}
</style>
