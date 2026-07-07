<script setup lang="ts">
import { computed } from 'vue'
import type { ProcessingStep, TaskBoardItemView } from '../../api/processingSteps'
import {
  formatDuration,
  formatStepLabel,
  resolveStepDurationMs,
  resolveStepHeaderText,
  stepLifecycle,
} from '../../api/processingSteps'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  live?: boolean
}>(), {
  live: false,
})

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const label = computed(() => formatStepLabel(props.step))
const headerText = computed(() => resolveStepHeaderText(props.step))
const tasks = computed(() => props.step.metadata?.tasks ?? [])
const taskProgress = computed(() => props.step.metadata?.taskProgress?.trim() || '')

const durationText = computed(() => {
  if (lifecycle.value !== 'done') return ''
  const ms = resolveStepDurationMs(props.step)
  return ms != null ? formatDuration(ms) : ''
})

const showShimmer = computed(() => isRunning.value && props.live)

function itemClass(item: TaskBoardItemView): Record<string, boolean> {
  return {
    'is-in-progress': item.status === 'in_progress',
    'is-completed': item.status === 'completed',
    'is-cancelled': item.status === 'cancelled',
    'is-pending': item.status === 'pending',
  }
}

function checkboxClass(item: TaskBoardItemView): Record<string, boolean> {
  return {
    'is-checked': item.status === 'completed',
    'is-cancelled': item.status === 'cancelled',
    'is-active': item.status === 'in_progress',
  }
}
</script>

<template>
  <div
    class="taskboard-line"
    :class="{ 'is-running': isRunning && live }"
  >
    <div class="taskboard-header">
      <span class="op-gutter" aria-hidden="true" />
      <span class="taskboard-main">
        <span class="taskboard-label" :class="{ 'op-shimmer': showShimmer }">{{ label }}</span>
        <span v-if="headerText" class="taskboard-summary" :class="{ 'op-shimmer': showShimmer }">
          {{ headerText }}<span v-if="isRunning && live" class="op-pulse">…</span>
        </span>
        <span v-else-if="taskProgress" class="taskboard-progress">{{ taskProgress }}</span>
      </span>
      <span v-if="durationText" class="taskboard-dur">{{ durationText }}</span>
    </div>

    <ul v-if="tasks.length" class="taskboard-list" role="list">
      <li
        v-for="item in tasks"
        :key="item.id"
        class="taskboard-item"
        :class="itemClass(item)"
      >
        <span
          class="taskboard-checkbox"
          :class="checkboxClass(item)"
          aria-hidden="true"
        />
        <span class="taskboard-content">{{ item.content }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.taskboard-line {
  --op-gutter: 12px;
  --op-font: var(--sun-font-md);
  --op-font-sm: var(--sun-font-sm);
  font-size: var(--op-font);
  line-height: 1.5;
  color: var(--sun-text-muted);
  padding: 1px 0 6px;
}

.taskboard-header {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr) auto;
  column-gap: 4px;
  align-items: start;
}

.op-gutter {
  width: var(--op-gutter);
  flex-shrink: 0;
}

.taskboard-main {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.taskboard-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.taskboard-line.is-running .taskboard-label:not(.op-shimmer) {
  color: var(--sun-text);
}

.taskboard-summary,
.taskboard-progress {
  flex: 1 1 0;
  min-width: 0;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.taskboard-progress {
  font-size: var(--op-font-sm);
  opacity: 0.75;
}

.taskboard-dur {
  flex-shrink: 0;
  padding-left: 10px;
  padding-top: 1px;
  font-size: var(--op-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.taskboard-list {
  list-style: none;
  margin: 6px 0 0 calc(var(--op-gutter) + 4px);
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: min(36vh, 280px);
  overflow-y: auto;
}

.taskboard-item {
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  column-gap: 8px;
  align-items: start;
  padding: 4px 2px;
  border-radius: 4px;
  color: var(--sun-text-secondary);
}

.taskboard-item.is-in-progress {
  color: var(--sun-text);
  background: color-mix(in srgb, var(--sun-border) 35%, transparent);
  border: 1px solid var(--sun-border);
  padding: 3px 5px;
}

.taskboard-item.is-completed .taskboard-content {
  color: var(--sun-text-muted);
  opacity: 0.82;
}

.taskboard-item.is-cancelled .taskboard-content {
  color: var(--sun-text-muted);
  opacity: 0.55;
  text-decoration: line-through;
}

.taskboard-checkbox {
  width: 14px;
  height: 14px;
  margin-top: 3px;
  border: 1.5px solid var(--sun-border);
  border-radius: 3px;
  box-sizing: border-box;
  position: relative;
  flex-shrink: 0;
}

.taskboard-checkbox.is-checked {
  border-color: color-mix(in srgb, var(--sun-text-muted) 70%, transparent);
  background: color-mix(in srgb, var(--sun-text-muted) 18%, transparent);
}

.taskboard-checkbox.is-checked::after {
  content: '';
  position: absolute;
  left: 3px;
  top: 1px;
  width: 5px;
  height: 8px;
  border: solid var(--sun-text-muted);
  border-width: 0 1.5px 1.5px 0;
  transform: rotate(45deg);
}

.taskboard-checkbox.is-active {
  border-color: var(--sun-text-secondary);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--sun-text-secondary) 40%, transparent);
}

.taskboard-checkbox.is-active::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 6px;
  height: 6px;
  margin: -3px 0 0 -3px;
  border-radius: 50%;
  background: var(--sun-text-secondary);
  animation: taskboard-pulse 1.2s ease-in-out infinite;
}

.taskboard-checkbox.is-cancelled {
  opacity: 0.45;
}

.taskboard-content {
  font-size: var(--sun-font-base);
  line-height: 1.45;
  word-break: break-word;
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

.taskboard-label.op-shimmer {
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

@keyframes taskboard-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.45; transform: scale(0.85); }
}
</style>
