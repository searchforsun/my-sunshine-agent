<script setup lang="ts">
import { computed, inject, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  formatStepLabel,
  resolveRunningChildStepBody,
  resolveStepDurationMs,
  stepLifecycle,
} from '../../api/processingSteps'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'
import { formatTaskUnitId } from '../../api/harnessTimeline'
import type { DagNodeStatus, DagNodeView } from '../../utils/planGraph'
import PlanNodeIcon from '../plan/PlanNodeIcon.vue'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  live?: boolean
}>(), {
  live: false,
})

const { open, isActivePlan, state: drawerState } = usePlanNodeDrawer()
/** 渲染在 PlanNodeDrawer 内的嵌套卡（worker 抽屉 → 子 agent 卡）：点击入栈，右上角逐层返回 */
const nestedInDrawer = inject<boolean>('planNodeDrawerNested', false)
const cancelSpawnSubagent = inject<(runId: string, stepId?: string) => void | Promise<void>>(
  'cancelSpawnSubagent',
  async () => {},
)

/** 抽屉打开时跟随 SSE 刷新状态（正文/子步骤持续累积） */
watch(
  () => props.step,
  (step) => {
    if (!isActivePlan(step.id)) return
    drawerState.step = step
    drawerState.node = toWorkerNode(step)
  },
  { deep: true },
)

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const isError = computed(() => lifecycle.value === 'error')
const isPaused = computed(() => lifecycle.value === 'paused' || lifecycle.value === 'terminated')
/** 执行单元记号（T1-1/T5-2）：从 step.id=worker-{taskId} 解析，显示在任务名称前 */
const taskId = computed(() => {
  const id = props.step.id || ''
  return id.startsWith('worker-') ? formatTaskUnitId(id.slice('worker-'.length)) : ''
})
const label = computed(() => formatStepLabel(props.step) || '执行单元')
const showShimmer = computed(() => isRunning.value && props.live)
/** 运行中当前子步正文（思考文本/工具输出等；generate 阶段显示「正在收尾回复」），任务名后跟随展示 */
const childStepBody = computed(() => {
  if (!showShimmer.value) return ''
  return resolveRunningChildStepBody(props.step)
})
const workerRunId = computed(() => props.step.metadata?.workerRunId?.trim() || '')
const canStop = computed(() => props.live && isRunning.value && !!workerRunId.value)

const statusKey = computed(() => {
  if (isPaused.value) return 'paused'
  if (isError.value) return 'error'
  if (isDone.value) return 'done'
  if (isRunning.value) return 'running'
  return 'pending'
})

const statusLabel = computed(() => {
  switch (statusKey.value) {
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
  if (isPaused.value) return 'paused'
  if (isError.value) return 'error'
  if (isDone.value) return 'done'
  if (isRunning.value) return 'running'
  return 'pending'
}

/** 复用 PlanNodeDrawer；synthetic worker 节点 */
function toWorkerNode(step: ProcessingStep): DagNodeView {
  return {
    id: step.id,
    type: 'worker',
    label: formatStepLabel(step) || '执行单元',
    status: toDagStatus(),
    durationMs: resolveStepDurationMs(step),
  }
}

function onOpen(): void {
  open(
    {
      planId: props.step.id,
      node: toWorkerNode(props.step),
      step: props.step,
    },
    { push: nestedInDrawer },
  )
}

async function onStop(e: Event): Promise<void> {
  e.stopPropagation()
  e.preventDefault()
  if (!workerRunId.value) return
  await cancelSpawnSubagent(workerRunId.value, props.step.id)
}
</script>

<template>
  <div
    class="worker-card-wrap"
    :class="{
      'is-running': isRunning && live,
      'is-active': isActivePlan(step.id),
      [`is-${statusKey}`]: true,
    }"  >
    <div
      class="worker-card"
      role="button"
      tabindex="0"
      :aria-label="`打开执行单元详情：${label}`"
      @click="onOpen"
      @keydown.enter.prevent="onOpen"
      @keydown.space.prevent="onOpen"
    >
      <span class="worker-icon" aria-hidden="true">
        <PlanNodeIcon type="worker" :size="15" />
      </span>
      <span class="worker-status" :class="`is-${statusKey}`">
        <span class="status-dot" aria-hidden="true" />
        {{ statusLabel }}
      </span>
      <span class="worker-main">
        <span class="worker-label" :class="{ 'op-shimmer': showShimmer }">
          <span v-if="taskId" class="worker-task-id">{{ taskId }}</span>{{ label }}
        </span>
        <span
          v-if="childStepBody"
          class="worker-child-label"
          :class="{ 'op-shimmer': showShimmer }"
        >
          {{ childStepBody }}
        </span>
      </span>
      <span class="worker-trailing">
        <button
          v-if="canStop"
          type="button"
          class="worker-stop"
          title="取消执行单元"
          aria-label="取消执行单元"
          @click="onStop"
        >
          <svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
            <rect x="4" y="4" width="8" height="8" rx="1" />
          </svg>
        </button>
        <span v-else-if="durationText" class="worker-dur">{{ durationText }}</span>
      </span>
    </div>
  </div>
</template>

<style scoped>
.worker-card-wrap {
  --panel-radius: var(--radius-sm, 6px);
  margin: 6px 0;
}

.worker-card {
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

.worker-card:hover {
  border-color: color-mix(in srgb, var(--sun-border) 55%, var(--sun-text-muted));
}

.worker-card:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 1px var(--sun-text-muted);
}

.worker-card-wrap.is-active .worker-card {
  box-shadow: inset 0 0 0 1px var(--sun-text-muted);
}

.worker-icon {
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

.worker-card-wrap.is-running .worker-icon {
  color: var(--sun-blue, #58a6ff);
  border-color: color-mix(in srgb, var(--sun-blue, #58a6ff) 40%, var(--sun-border));
}

.worker-card-wrap.is-done .worker-icon {
  color: var(--sun-green, #3fb950);
}

.worker-card-wrap.is-error .worker-icon {
  color: var(--sun-red, #f85149);
}

.worker-card-wrap.is-paused .worker-icon {
  color: var(--sun-text-secondary);
}

.worker-status {
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

.worker-status.is-pending { color: var(--sun-text-muted); }
.worker-status.is-running { color: var(--sun-blue, #58a6ff); }
.worker-status.is-done { color: var(--sun-green, #3fb950); }
.worker-status.is-error { color: var(--sun-red, #f85149); }
.worker-status.is-paused { color: #ca8a04; }

.worker-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.worker-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.worker-task-id {
  display: inline-block;
  margin-right: 6px;
  font-family: var(--sun-font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: calc(var(--sun-font-sm) * 0.92);
  font-weight: 600;
  line-height: 1.4;
  color: var(--sun-text-secondary);
  vertical-align: baseline;
}

.worker-card-wrap.is-running .worker-label:not(.op-shimmer) {
  color: var(--sun-text);
}

/* 运行中当前子步正文（思考文本/工具输出等）：样式同 subagent summary 行，无圆点 */
.worker-child-label {
  flex: 1 1 0;
  min-width: 0;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.worker-trailing {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 28px;
}

.worker-dur {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* Chat 底栏同款：圆形外框 + 圆角方块（与 SubagentCard 一致） */
.worker-stop {
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

.worker-stop:hover {
  color: var(--sun-red, #f85149);
  border-color: var(--sun-red, #f85149);
  background: rgba(248, 113, 113, 0.08);
}

.op-shimmer {
  --op-shimmer-base: var(--sun-shimmer-base);
  --op-shimmer-peak: var(--sun-shimmer-peak);
  display: inline-block;
  max-width: 100%;
  background-image: linear-gradient(
    90deg,
    var(--op-shimmer-base) 0%,
    var(--op-shimmer-base) 38%,
    var(--op-shimmer-peak) 50%,
    var(--op-shimmer-base) 62%,
    var(--op-shimmer-base) 100%
  );
  background-size: 220% 100%;
  background-repeat: no-repeat;
  background-position: 100% center;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: op-text-shimmer 1.2s linear infinite;
}

.worker-label.op-shimmer {
  --op-shimmer-base: var(--sun-shimmer-label-base);
  --op-shimmer-peak: var(--sun-shimmer-label-peak);
}

@keyframes op-text-shimmer {
  0% { background-position: 100% center; }
  100% { background-position: 0% center; }
}
</style>
