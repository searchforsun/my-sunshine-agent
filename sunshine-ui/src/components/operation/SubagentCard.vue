<script setup lang="ts">
import { computed, inject, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  formatStepLabel,
  resolveStepDurationMs,
  resolveStepHeaderText,
  stepLifecycle,
} from '../../api/processingSteps'
import { stepHasHitlAwaiting } from '../../api/recoverySteps'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'
import type { DagNodeStatus, DagNodeView } from '../../utils/planGraph'
import PlanNodeIcon from '../plan/PlanNodeIcon.vue'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  live?: boolean
}>(), {
  live: false,
})

const { open, isActivePlan, state: drawerState } = usePlanNodeDrawer()
const cancelSpawnSubagent = inject<(runId: string) => void | Promise<void>>(
  'cancelSpawnSubagent',
  async () => {},
)

/** 抽屉打开时跟随 SSE 刷新状态（避免取消后仍显示运行中） */
watch(
  () => props.step,
  (step) => {
    if (!isActivePlan(step.id)) return
    drawerState.step = step
    drawerState.node = toAgentNode(step)
  },
  { deep: true },
)

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const isError = computed(() => lifecycle.value === 'error')
const isPaused = computed(() => lifecycle.value === 'paused' || lifecycle.value === 'terminated')
const awaitingConfirm = computed(() => stepHasHitlAwaiting(props.step))
const label = computed(() => formatStepLabel(props.step) || '子任务')
const headerText = computed(() => resolveStepHeaderText(props.step))
const showShimmer = computed(() => isRunning.value && props.live && !awaitingConfirm.value)
const canStop = computed(() => props.live && (isRunning.value || awaitingConfirm.value))

const statusKey = computed(() => {
  if (awaitingConfirm.value) return 'awaiting_confirm'
  if (isPaused.value) return 'paused'
  if (isError.value) return 'error'
  if (isDone.value) return 'done'
  if (isRunning.value) return 'running'
  return 'pending'
})

const statusLabel = computed(() => {
  switch (statusKey.value) {
    case 'awaiting_confirm': return '待确认'
    case 'paused': return props.step.summary?.after?.trim() || '已取消'
    case 'error': return '失败'
    case 'done': return '完成'
    case 'running': return '运行中'
    default: return '等待中'
  }
})

const durationText = computed(() => {
  if (!isDone.value && !isError.value && !isPaused.value) return ''
  const ms = resolveStepDurationMs(props.step)
  return ms != null ? formatDuration(ms) : ''
})

function toDagStatus(): DagNodeStatus {
  if (awaitingConfirm.value) return 'awaiting_confirm'
  if (isPaused.value) return 'paused'
  if (isError.value) return 'error'
  if (isDone.value) return 'done'
  if (isRunning.value) return 'running'
  return 'pending'
}

/** 复用 PlanNodeDrawer（含沙箱三列宽）；synthetic agent 节点 */
function toAgentNode(step: ProcessingStep): DagNodeView {
  return {
    id: step.id,
    type: 'agent',
    label: formatStepLabel(step) || '子任务',
    status: toDagStatus(),
    durationMs: resolveStepDurationMs(step),
  }
}

function onOpen(): void {
  open({
    planId: props.step.id,
    node: toAgentNode(props.step),
    step: props.step,
  })
}

function parseSubagentRunId(stepId: string): string | null {
  if (!stepId?.startsWith('subagent-')) return null
  const runId = stepId.slice('subagent-'.length).trim()
  return runId || null
}

async function onStop(e: Event): Promise<void> {
  e.stopPropagation()
  e.preventDefault()
  const runId = parseSubagentRunId(props.step.id)
  if (!runId) return
  await cancelSpawnSubagent(runId)
}
</script>

<template>
  <div
    class="subagent-card-wrap"
    :class="{
      'is-running': isRunning && live && !awaitingConfirm,
      'is-active': isActivePlan(step.id),
      [`is-${statusKey}`]: true,
    }"
  >
    <div
      class="subagent-card"
      role="button"
      tabindex="0"
      :aria-label="`打开子任务详情：${label}`"
      @click="onOpen"
      @keydown.enter.prevent="onOpen"
      @keydown.space.prevent="onOpen"
    >
      <span class="subagent-icon" aria-hidden="true">
        <PlanNodeIcon type="agent" :size="15" />
      </span>
      <span class="subagent-status" :class="`is-${statusKey}`">
        <span class="status-dot" aria-hidden="true" />
        {{ statusLabel }}
      </span>
      <span class="subagent-main">
        <span class="subagent-label" :class="{ 'op-shimmer': showShimmer }">{{ label }}</span>
        <span
          v-if="headerText"
          class="subagent-summary"
          :class="{ 'op-shimmer': showShimmer }"
        >
          {{ headerText }}<span v-if="isRunning && live && !awaitingConfirm" class="op-pulse">…</span>
        </span>
      </span>
      <span class="subagent-trailing">
        <button
          v-if="canStop"
          type="button"
          class="subagent-stop"
          title="取消子任务"
          aria-label="取消子任务"
          @click="onStop"
        >
          <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <rect x="4" y="4" width="8" height="8" rx="1" />
          </svg>
        </button>
        <span v-else-if="durationText" class="subagent-dur">{{ durationText }}</span>
      </span>
    </div>
  </div>
</template>

<style scoped>
.subagent-card-wrap {
  --op-gutter: 12px;
  --panel-radius: var(--radius-sm, 6px);
  margin: 6px 0;
  padding-left: calc(var(--op-gutter) + 4px);
}

.subagent-card {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr) auto;
  column-gap: 8px;
  align-items: center;
  width: 100%;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--panel-radius);
  background: var(--sun-black);
  color: var(--sun-text-muted);
  font-size: var(--sun-font-md);
  line-height: 1.45;
  text-align: left;
  cursor: pointer;
}

.subagent-card:hover {
  border-color: color-mix(in srgb, var(--sun-border) 55%, var(--sun-text-muted));
}

.subagent-card:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 1px var(--sun-text-muted);
}

.subagent-card-wrap.is-active .subagent-card {
  box-shadow: inset 0 0 0 1px var(--sun-text-muted);
}

.subagent-icon {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  border: 1px solid var(--sun-border);
  color: var(--sun-text-secondary);
}

.subagent-card-wrap.is-running .subagent-icon {
  color: var(--sun-blue, #58a6ff);
  border-color: color-mix(in srgb, var(--sun-blue, #58a6ff) 40%, var(--sun-border));
}

.subagent-card-wrap.is-done .subagent-icon {
  color: var(--sun-green, #3fb950);
}

.subagent-card-wrap.is-error .subagent-icon {
  color: var(--sun-red, #f85149);
}

.subagent-card-wrap.is-paused .subagent-icon,
.subagent-card-wrap.is-awaiting_confirm .subagent-icon {
  color: var(--sun-text-secondary);
}

.subagent-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text-muted);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}

.subagent-status.is-pending { color: var(--sun-text-muted); }
.subagent-status.is-running { color: var(--sun-blue, #58a6ff); }
.subagent-status.is-awaiting_confirm { color: var(--sun-purple, #9333ea); }
.subagent-status.is-done { color: var(--sun-green, #3fb950); }
.subagent-status.is-error { color: var(--sun-red, #f85149); }
.subagent-status.is-paused { color: var(--sun-text-muted); }

.subagent-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.subagent-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.subagent-card-wrap.is-running .subagent-label:not(.op-shimmer) {
  color: var(--sun-text);
}

.subagent-summary {
  flex: 1 1 0;
  min-width: 0;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.subagent-trailing {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 28px;
}

.subagent-dur {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* Chat 底栏同款：圆形外框 + 圆角方块 */
.subagent-stop {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  margin: 0;
  padding: 0;
  border: 1px solid var(--sun-border);
  border-radius: 50%;
  background: transparent;
  color: var(--sun-text-secondary);
  cursor: pointer;
  line-height: 0;
}

.subagent-stop:hover {
  color: var(--sun-red, #f85149);
  border-color: var(--sun-red, #f85149);
  background: rgba(248, 113, 113, 0.08);
}

.op-shimmer {
  --op-shimmer-base: var(--sun-text-muted);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text-muted) 32%, white);
  display: inline-block;
  max-width: 100%;
  background-image: linear-gradient(
    90deg,
    var(--op-shimmer-base) 0%,
    var(--op-shimmer-base) 36%,
    var(--op-shimmer-peak) 50%,
    var(--op-shimmer-base) 64%,
    var(--op-shimmer-base) 100%
  );
  background-size: 220% 100%;
  background-repeat: no-repeat;
  background-position: 100% center;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: op-text-shimmer 2.6s linear infinite;
}

.subagent-label.op-shimmer {
  --op-shimmer-base: var(--sun-text);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text) 22%, white);
}

.op-pulse {
  animation: op-pulse 1.2s ease-in-out infinite;
}

@keyframes op-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

@keyframes op-text-shimmer {
  0% { background-position: 100% center; }
  100% { background-position: 0% center; }
}
</style>
