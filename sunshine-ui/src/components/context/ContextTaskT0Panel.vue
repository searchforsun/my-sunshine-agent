<script setup lang="ts">
import { computed, inject } from 'vue'
import { NEmpty, NSpin } from 'naive-ui'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi

const snapshot = computed(() => page.taskBoardSnapshot)
const items = computed(() => snapshot.value?.items ?? [])
const doneCount = computed(() => items.value.filter(i => i.status === 'completed').length)
const total = computed(() => items.value.length)

function itemClass(status: string): Record<string, boolean> {
  return {
    'is-in-progress': status === 'in_progress',
    'is-completed': status === 'completed',
    'is-cancelled': status === 'cancelled',
    'is-pending': status === 'pending',
  }
}

function markerClass(status: string): Record<string, boolean> {
  return {
    'is-done': status === 'completed',
    'is-active': status === 'in_progress',
    'is-cancelled': status === 'cancelled',
    'is-pending': status === 'pending',
  }
}
</script>

<template>
  <template v-if="!page.selectedConv">
    <div class="empty-wrap fill">
      <NEmpty size="small" description="请先选择任务会话" />
    </div>
  </template>
  <NSpin v-else :show="page.loadingTaskBoard" class="tab-spin">
    <div v-if="snapshot" class="t0-body">
      <header class="t0-head">
        <span class="t0-tag">最近一次快照</span>
        <span class="t0-meta">{{ page.formatTime(snapshot.updatedAt) }} · {{ total }} 项</span>
      </header>
      <div class="t0-progress-label">
        {{ doneCount }} / {{ total }} 已完成
      </div>
      <ul v-if="items.length" class="taskboard-list" role="list">
        <li
          v-for="item in items"
          :key="item.id"
          class="taskboard-item"
          :class="itemClass(item.status)"
        >
          <span class="taskboard-marker" :class="markerClass(item.status)" aria-hidden="true">
            <svg v-if="item.status === 'completed'" class="marker-icon" viewBox="0 0 16 16">
              <path d="M3.5 8.2 6.5 11.2 12.5 5.2" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <svg v-else-if="item.status === 'in_progress'" class="marker-icon" viewBox="0 0 16 16">
              <path d="M6.5 4.5 10.5 8 6.5 11.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <svg v-else-if="item.status === 'cancelled'" class="marker-icon" viewBox="0 0 16 16">
              <path d="M5.2 5.2 10.8 10.8M10.8 5.2 5.2 10.8" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
            </svg>
          </span>
          <span v-if="item.dependsOn?.length" class="taskboard-dep">依赖:{{ item.dependsOn.join(',') }}</span>
          <span class="taskboard-content">{{ item.content }}</span>
        </li>
      </ul>
      <div v-else class="empty-wrap">
        <NEmpty size="small" description="该会话暂无任务快照" />
      </div>
    </div>
    <div v-else class="empty-wrap fill">
      <NEmpty size="small" description="该会话暂无任务进度" />
    </div>
  </NSpin>
</template>

<style scoped>
.tab-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}

.tab-spin :deep(.n-spin-container),
.tab-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.t0-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 0 8px;
}

.t0-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.t0-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid color-mix(in srgb, #7a8fa3 35%, transparent);
  background: color-mix(in srgb, #7a8fa3 18%, transparent);
  color: var(--sun-text-secondary);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.t0-meta {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.t0-progress-label {
  font-size: 13px;
  color: var(--sun-text);
  margin-bottom: 10px;
  font-variant-numeric: tabular-nums;
}

.taskboard-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.taskboard-item {
  display: grid;
  grid-template-columns: 16px minmax(0, 1fr);
  column-gap: 8px;
  align-items: start;
}

.taskboard-marker {
  width: 15px;
  height: 15px;
  margin-top: 2px;
  border-radius: 50%;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.taskboard-marker.is-pending {
  border: 1.5px dashed color-mix(in srgb, var(--sun-text-muted) 50%, transparent);
}

.taskboard-marker.is-active {
  border: 1.5px solid var(--sun-text-secondary);
}

.taskboard-marker.is-done {
  border: 1.5px solid color-mix(in srgb, var(--sun-text-muted) 60%, transparent);
  background: color-mix(in srgb, var(--sun-text-muted) 10%, transparent);
}

.taskboard-marker.is-cancelled {
  border: 1.5px dashed color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
  opacity: 0.55;
}

.marker-icon {
  width: 10px;
  height: 10px;
}

.taskboard-dep {
  grid-column: 2;
  font-size: 11px;
  color: var(--sun-text-muted);
}

.taskboard-content {
  font-size: var(--sun-font-sm);
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

.empty-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  width: 100%;
}

.empty-wrap.fill {
  min-height: 0;
  height: 100%;
  align-self: stretch;
}
</style>
