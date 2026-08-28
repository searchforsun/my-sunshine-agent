<script setup lang="ts">
import { computed, inject } from 'vue'
import { NEmpty, NSpin } from 'naive-ui'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'
import { formatTaskUnitId } from '../../api/harnessTimeline'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi

const notebook = computed(() => page.h1Notebook)
const queue = computed(() => notebook.value?.taskQueue ?? [])
const rounds = computed(() => notebook.value?.rounds ?? [])
const progress = computed(() => {
  const v = notebook.value?.goalCompletion ?? 0
  return Math.round(v * 100)
})

function itemClass(status: string): Record<string, boolean> {
  return {
    'is-in-progress': status === 'in_progress',
    'is-done': status === 'done',
    'is-fail': status === 'fail',
    'is-cancelled': status === 'cancelled' || status === 'obsolete',
    'is-pending': status === 'pending',
  }
}

function markerClass(status: string): Record<string, boolean> {
  return {
    'is-done': status === 'done',
    'is-active': status === 'in_progress',
    'is-fail': status === 'fail',
    'is-cancelled': status === 'cancelled' || status === 'obsolete',
    'is-pending': status === 'pending',
  }
}

function roundStatusText(status: string): string {
  if (status === 'done') return '完成'
  if (status === 'fail') return '失败'
  if (status === 'in_progress') return '进行中'
  if (status === 'cancelled') return '取消'
  if (status === 'obsolete') return '废弃'
  return status
}
</script>

<template>
  <template v-if="!page.selectedConv">
    <div class="empty-wrap fill">
      <NEmpty size="small" description="请先选择任务会话" />
    </div>
  </template>
  <NSpin v-else :show="page.loadingH1" class="tab-spin">
    <div v-if="notebook" class="h1-body">
      <section class="h1-goal">
        <div class="h1-goal-head">
          <span class="h1-tag">Goal</span>
          <span class="h1-meta">第 {{ notebook.currentRound }} 轮 · 已完成 {{ notebook.totalTasksCompleted }} 项</span>
        </div>
        <p class="h1-goal-text">{{ notebook.originalGoal || '（空）' }}</p>
        <div class="h1-progress-bar">
          <div class="h1-progress-fill" :style="{ width: progress + '%' }" />
        </div>
        <div class="h1-progress-num">{{ progress }}%</div>
      </section>

      <section v-if="notebook.nextDirection" class="h1-block">
        <div class="h1-block-title">下一步方向</div>
        <p class="h1-block-text">{{ notebook.nextDirection }}</p>
      </section>

      <section class="h1-block">
        <div class="h1-block-title">
          任务队列
          <span class="h1-count">{{ queue.length }}</span>
        </div>
        <ul v-if="queue.length" class="h1-task-list" role="list">
          <li
            v-for="item in queue"
            :key="item.taskId"
            class="h1-task-item"
            :class="itemClass(item.status)"
          >
            <span class="h1-task-marker" :class="markerClass(item.status)" aria-hidden="true" />
            <span class="h1-task-id">{{ formatTaskUnitId(item.taskId) }}</span>
            <span class="h1-task-content">{{ item.label }}</span>
            <span class="h1-task-status">{{ roundStatusText(item.status) }}</span>
          </li>
        </ul>
        <div v-else class="empty-wrap">
          <NEmpty size="small" description="任务队列为空" />
        </div>
      </section>

      <section v-if="rounds.length" class="h1-block">
        <div class="h1-block-title">
          轮次记录
          <span class="h1-count">{{ rounds.length }}</span>
        </div>
        <div class="h1-round-list">
          <article
            v-for="round in rounds"
            :key="round.roundIndex"
            class="h1-round"
          >
            <header class="h1-round-head">
              <span class="h1-round-idx">R{{ round.roundIndex }}</span>
              <span class="h1-round-meta">完成度 {{ Math.round(round.roundGoalCompletion * 100) }}%</span>
            </header>
            <p v-if="round.assessReason" class="h1-round-reason">{{ round.assessReason }}</p>
            <ul v-if="round.nodeResults?.length" class="h1-node-list" role="list">
              <li
                v-for="node in round.nodeResults"
                :key="node.nodeId"
                class="h1-node-item"
              >
                <span class="h1-node-name">{{ node.nodeId }}</span>
                <span class="h1-node-status">{{ roundStatusText(node.status) }}</span>
                <span class="h1-node-summary">{{ node.summary || '' }}</span>
              </li>
            </ul>
          </article>
        </div>
      </section>
    </div>
    <div v-else class="empty-wrap fill">
      <NEmpty size="small" description="该会话暂无计划笔记本（Pro 执行时生成）" />
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

.h1-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 0 8px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.h1-goal {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  background: var(--sun-black);
}

.h1-goal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.h1-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid color-mix(in srgb, #9aa06e 35%, transparent);
  background: color-mix(in srgb, #9aa06e 18%, transparent);
  color: var(--sun-text-secondary);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
}

.h1-meta {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.h1-goal-text {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--sun-text);
  word-break: break-word;
  white-space: pre-wrap;
}

.h1-progress-bar {
  height: 6px;
  border-radius: 3px;
  background: color-mix(in srgb, var(--sun-border) 60%, transparent);
  overflow: hidden;
}

.h1-progress-fill {
  height: 100%;
  background: var(--sun-accent);
  border-radius: 3px;
  transition: width 0.3s ease;
}

.h1-progress-num {
  margin-top: 4px;
  font-size: 12px;
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
}

.h1-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.h1-block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-secondary);
  display: flex;
  align-items: center;
  gap: 6px;
}

.h1-count {
  display: inline-flex;
  align-items: center;
  height: 18px;
  min-width: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: color-mix(in srgb, var(--sun-border) 60%, transparent);
  color: var(--sun-text-muted);
  font-size: 11px;
  line-height: 1;
}

.h1-block-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--sun-text);
  word-break: break-word;
  white-space: pre-wrap;
}

.h1-task-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.h1-task-item {
  display: grid;
  grid-template-columns: 15px auto minmax(0, 1fr) auto;
  column-gap: 8px;
  align-items: start;
}

.h1-task-marker {
  width: 15px;
  height: 15px;
  margin-top: 2px;
  border-radius: 50%;
  box-sizing: border-box;
  flex-shrink: 0;
}

.h1-task-marker.is-pending {
  border: 1.5px dashed color-mix(in srgb, var(--sun-text-muted) 50%, transparent);
}

.h1-task-marker.is-active {
  border: 1.5px solid var(--sun-text-secondary);
}

.h1-task-marker.is-done {
  border: 1.5px solid color-mix(in srgb, var(--sun-text-muted) 60%, transparent);
  background: color-mix(in srgb, var(--sun-text-muted) 10%, transparent);
}

.h1-task-marker.is-fail {
  border: 1.5px solid color-mix(in srgb, #c0564f 60%, transparent);
  background: color-mix(in srgb, #c0564f 15%, transparent);
}

.h1-task-marker.is-cancelled {
  border: 1.5px dashed color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
  opacity: 0.55;
}

.h1-task-id {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
}

.h1-task-content {
  font-size: var(--sun-font-sm);
  line-height: 1.45;
  word-break: break-word;
  color: var(--sun-text-muted);
}

.h1-task-status {
  font-size: 11px;
  color: var(--sun-text-muted);
  white-space: nowrap;
}

.h1-task-item.is-in-progress .h1-task-content {
  color: var(--sun-text);
}

.h1-task-item.is-done .h1-task-content {
  text-decoration: line-through;
}

.h1-task-item.is-fail .h1-task-content {
  color: color-mix(in srgb, #c0564f 80%, var(--sun-text));
}

.h1-round-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.h1-round {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  background: var(--sun-black);
}

.h1-round-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.h1-round-idx {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-secondary);
}

.h1-round-meta {
  font-size: 11px;
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
}

.h1-round-reason {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-muted);
  word-break: break-word;
}

.h1-node-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.h1-node-item {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  column-gap: 8px;
  align-items: start;
}

.h1-node-name {
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.h1-node-status {
  font-size: 11px;
  color: var(--sun-text-muted);
  white-space: nowrap;
}

.h1-node-summary {
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-muted);
  word-break: break-word;
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
