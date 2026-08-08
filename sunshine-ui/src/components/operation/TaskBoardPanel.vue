<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ProcessingStep, TaskBoardItemView } from '../../api/processingSteps'
import { hasRealTaskBoardItems, stepLifecycle } from '../../api/processingSteps'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  live?: boolean
  /** 初始折叠（悬浮场景默认收一行进度，避免遮挡输入区） */
  defaultCollapsed?: boolean
  /** 悬浮态：去掉左侧 gutter，横向撑满，hover 时头部图标切换为展开/收起箭头 */
  floating?: boolean
}>(), {
  live: false,
  defaultCollapsed: false,
  floating: false,
})

const expanded = ref(!props.defaultCollapsed)
const userToggled = ref(false)

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isPending = computed(() => lifecycle.value === 'pending')
const tasks = computed(() => props.step.metadata?.tasks ?? [])
const hasRealTasks = computed(() => hasRealTaskBoardItems(props.step))
const showPanel = computed(() => hasRealTasks.value)

const doneCount = computed(() =>
  tasks.value.filter(t => t.status === 'completed').length,
)

/** 卡片头：对齐参考图 "1 of 4 Done" */
const progressLabel = computed(() => {
  if (!hasRealTasks.value) {
    return props.step.summary?.before?.trim() || '规划任务清单'
  }
  const progress = props.step.metadata?.taskProgress?.trim()
  if (progress) return progress
  const total = tasks.value.length
  if (!total) return '规划任务清单'
  return `${doneCount.value} of ${total} Done`
})

watch(isRunning, running => {
  if (userToggled.value) return
  expanded.value = running
})

function toggleExpand(): void {
  userToggled.value = true
  expanded.value = !expanded.value
}

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
    v-if="showPanel"
    class="taskboard-wrap"
    :class="{
      'is-expanded': expanded,
      'is-collapsed': !expanded,
      'is-running': isRunning && live,
      'is-floating': floating,
    }"
  >
    <div class="taskboard-row">
      <div class="taskboard-card">
        <button
          type="button"
          class="taskboard-card-head"
          :aria-expanded="expanded"
          @click="toggleExpand"
        >
          <svg class="taskboard-list-icon" viewBox="0 0 16 16" aria-hidden="true">
            <path d="M4 2.5h8l1.5 2v9a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13.5V4.5L4 2.5Z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
            <path d="M5.5 8h5M5.5 10.5h3" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
          </svg>
          <svg class="taskboard-head-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <polyline points="9 18 15 12 9 6" />
          </svg>
          <span class="taskboard-progress">{{ progressLabel }}</span>
        </button>
        <ul v-show="expanded && hasRealTasks" class="taskboard-list" role="list">
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
  </div>
</template>

<style scoped>
.taskboard-wrap {
  --panel-radius: var(--radius-sm, 6px);
  margin: 6px 0;
}

/* 悬浮态：卡片横向撑满；悬浮面板自身已有外边框，内部卡片去边框 */
.taskboard-wrap.is-floating {
  margin: 0;
}

.taskboard-wrap.is-floating .taskboard-card {
  border: none;
  background: transparent;
}

.taskboard-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
}

.taskboard-wrap.is-collapsed .taskboard-row {
  align-items: center;
}

.taskboard-card {
  min-width: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--panel-radius);
  background: var(--sun-black);
}

/* 普通态卡片左缘与上下时间线文字对齐（gutter 已移除，无需偏移） */
.taskboard-wrap:not(.is-floating) {
  margin-left: 0;
}

.taskboard-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin: 0;
  padding: 8px 10px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
  line-height: 1.35;
  text-align: left;
  cursor: pointer;
}

.taskboard-wrap.is-collapsed .taskboard-card-head {
  padding: 6px 10px;
  min-height: 28px;
}

.taskboard-wrap.is-expanded .taskboard-card-head {
  padding-bottom: 6px;
}

.taskboard-card-head:hover .taskboard-progress {
  color: var(--sun-text-secondary);
}

.taskboard-progress {
  flex: 1;
  min-width: 0;
  font-variant-numeric: tabular-nums;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 行首三条杠：默认常驻；hover 时切换为展开箭头（同 chat 输入框上方悬浮 todolist 样式） */
.taskboard-list-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  opacity: 0.72;
}

/* hover 切换：三条杠隐藏，显示 > / ^ 箭头；与三条杠同宽（14px）居中，避免 progress 左右移动 */
.taskboard-head-chevron {
  display: none;
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  box-sizing: border-box;
  padding: 1px;
  color: var(--sun-text-secondary);
  transition: transform 0.15s ease;
}

.taskboard-card-head:hover .taskboard-list-icon {
  display: none;
}

.taskboard-card-head:hover .taskboard-head-chevron {
  display: block;
}

/* 折叠态右箭头 >，展开态上箭头 ^ */
.taskboard-wrap.is-collapsed .taskboard-card-head:hover .taskboard-head-chevron {
  transform: rotate(0deg);
}

.taskboard-wrap.is-expanded .taskboard-card-head:hover .taskboard-head-chevron {
  transform: rotate(-90deg);
}

.taskboard-list {
  list-style: none;
  margin: 0;
  padding: 0 10px 8px;
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

.taskboard-wrap.is-running .taskboard-item.is-in-progress .taskboard-marker.is-active {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-secondary) 16%, transparent);
}
</style>
