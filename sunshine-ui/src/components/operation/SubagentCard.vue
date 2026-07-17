<script setup lang="ts">
import { computed, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  formatStepLabel,
  resolveStepDurationMs,
  resolveStepHeaderText,
  stepLifecycle,
} from '../../api/processingSteps'
import { stepHasHitlAwaiting } from '../../api/recoverySteps'
import { useSubagentDrawer } from '../../composables/useSubagentDrawer'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  live?: boolean
}>(), {
  live: false,
})

const { open, syncIfOpen, isActive } = useSubagentDrawer()

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const isError = computed(() => lifecycle.value === 'error')
const awaitingConfirm = computed(() => stepHasHitlAwaiting(props.step))
const label = computed(() => formatStepLabel(props.step) || '子任务')
const headerText = computed(() => resolveStepHeaderText(props.step))
const showShimmer = computed(() => isRunning.value && props.live && !awaitingConfirm.value)

const statusKey = computed(() => {
  if (awaitingConfirm.value) return 'awaiting_confirm'
  if (isError.value) return 'error'
  if (isDone.value) return 'done'
  if (isRunning.value) return 'running'
  return 'pending'
})

const statusLabel = computed(() => {
  switch (statusKey.value) {
    case 'awaiting_confirm': return '待确认'
    case 'error': return '失败'
    case 'done': return '完成'
    case 'running': return '运行中'
    default: return '等待中'
  }
})

const durationText = computed(() => {
  if (!isDone.value && !isError.value) return ''
  const ms = resolveStepDurationMs(props.step)
  return ms != null ? formatDuration(ms) : ''
})

watch(
  () => props.step,
  (step) => { syncIfOpen(step) },
  { deep: true },
)

function onOpen(): void {
  open(props.step)
}
</script>

<template>
  <div
    class="subagent-card-wrap"
    :class="{
      'is-running': isRunning && live && !awaitingConfirm,
      'is-active': isActive(step.id),
      [`is-${statusKey}`]: true,
    }"
  >
    <button
      type="button"
      class="subagent-card"
      :aria-label="`打开子任务详情：${label}`"
      @click="onOpen"
    >
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
      <span v-if="durationText" class="subagent-dur">{{ durationText }}</span>
    </button>
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
  grid-template-columns: auto minmax(0, 1fr) auto;
  column-gap: 10px;
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

.subagent-card-wrap.is-active .subagent-card {
  box-shadow: inset 0 0 0 1px var(--sun-text-muted);
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

.subagent-dur {
  flex-shrink: 0;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
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
