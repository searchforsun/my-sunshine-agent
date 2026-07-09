<script setup lang="ts">
import { computed } from 'vue'
import type { ProcessingStep, TaskBoardItemView } from '../../api/processingSteps'
import { stepLifecycle } from '../../api/processingSteps'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  live?: boolean
}>(), {
  live: false,
})

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const tasks = computed(() => props.step.metadata?.tasks ?? [])

const progressLabel = computed(() => {
  const progress = props.step.metadata?.taskProgress?.trim()
  if (progress) return progress
  const items = tasks.value
  if (!items.length) return ''
  const done = items.filter(t => t.status === 'completed').length
  return `${done} / ${items.length} 已完成`
})

function itemClass(item: TaskBoardItemView): Record<string, boolean> {
  return {
    'is-in-progress': item.status === 'in_progress',
    'is-completed': item.status === 'completed',
    'is-cancelled': item.status === 'cancelled',
    'is-pending': item.status === 'pending',
  }
}

function markerClass(item: TaskBoardItemView): Record<string, boolean> {
  return {
    'is-done': item.status === 'completed',
    'is-active': item.status === 'in_progress',
    'is-cancelled': item.status === 'cancelled',
    'is-pending': item.status === 'pending',
  }
}
</script>

<template>
  <div
    v-if="tasks.length"
    class="taskboard-wrap"
    :class="{ 'is-running': isRunning && live }"
  >
    <span class="op-gutter" aria-hidden="true" />
    <div class="taskboard-card">
      <div class="taskboard-card-head">
        <svg class="taskboard-icon" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M2 4.5h12M2 8h8M2 11.5h10" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
          <circle cx="12.5" cy="8" r="1.1" fill="currentColor" />
          <circle cx="12.5" cy="11.5" r="1.1" fill="currentColor" />
        </svg>
        <span class="taskboard-progress">{{ progressLabel }}</span>
      </div>
      <ul class="taskboard-list" role="list">
        <li
          v-for="item in tasks"
          :key="item.id"
          class="taskboard-item"
          :class="itemClass(item)"
        >
          <span class="taskboard-marker" :class="markerClass(item)" aria-hidden="true">
            <svg v-if="item.status === 'completed'" class="marker-icon" viewBox="0 0 16 16">
              <path d="M3.5 8.2 6.5 11.2 12.5 5.2" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <svg v-else-if="item.status === 'in_progress'" class="marker-icon" viewBox="0 0 16 16">
              <path d="M6.5 4.5 10.5 8 6.5 11.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>
          <span class="taskboard-content">{{ item.content }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.taskboard-wrap {
  --op-gutter: 12px;
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
  padding: 2px 0 10px;
}

.op-gutter {
  width: var(--op-gutter);
  flex-shrink: 0;
}

.taskboard-card {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: var(--sun-black);
}

.taskboard-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
  line-height: 1.35;
}

.taskboard-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  opacity: 0.72;
}

.taskboard-progress {
  font-variant-numeric: tabular-nums;
}

.taskboard-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: min(36vh, 280px);
  overflow-y: auto;
}

.taskboard-item {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  column-gap: 10px;
  align-items: start;
}

.taskboard-marker {
  width: 16px;
  height: 16px;
  margin-top: 2px;
  border-radius: 50%;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.taskboard-marker.is-pending {
  border: 1.5px dashed color-mix(in srgb, var(--sun-text-muted) 55%, transparent);
}

.taskboard-marker.is-active {
  border: 1.5px solid var(--sun-text-secondary);
}

.taskboard-marker.is-done {
  border: 1.5px solid color-mix(in srgb, var(--sun-text-muted) 65%, transparent);
  background: color-mix(in srgb, var(--sun-text-muted) 12%, transparent);
}

.taskboard-marker.is-cancelled {
  border: 1.5px dashed color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
  opacity: 0.55;
}

.marker-icon {
  width: 11px;
  height: 11px;
}

.taskboard-content {
  font-size: var(--sun-font-base);
  line-height: 1.45;
  word-break: break-word;
  color: var(--sun-text-muted);
}

.taskboard-item.is-in-progress .taskboard-content {
  color: var(--sun-text);
}

.taskboard-item.is-completed .taskboard-content {
  color: var(--sun-text-muted);
  opacity: 0.72;
  text-decoration: line-through;
}

.taskboard-item.is-cancelled .taskboard-content {
  opacity: 0.5;
  text-decoration: line-through;
}

.taskboard-wrap.is-running .taskboard-item.is-in-progress .taskboard-marker.is-active {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-secondary) 18%, transparent);
}
</style>
