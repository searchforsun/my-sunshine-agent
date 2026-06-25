<script setup lang="ts">
import { computed } from 'vue'
import { formatDuration } from '../../api/processingSteps'
import PlanNodeIcon from './PlanNodeIcon.vue'
import type { DagNodeView } from '../../utils/planGraph'

const props = defineProps<{
  nodes: DagNodeView[]
  selectedId?: string
  live?: boolean
  fluid?: boolean
  showExpand?: boolean
  /** 布局级缩放（矢量清晰，非 transform scale） */
  zoom?: number
}>()

const emit = defineEmits<{
  select: [node: DagNodeView]
  expand: []
}>()

const visibleNodes = computed(() => props.nodes.filter(n => n.label))

const zoomFactor = computed(() => (props.zoom != null && props.zoom > 0 ? props.zoom : 1))
const hasZoom = computed(() => props.zoom != null)
const rootStyle = computed(() => (hasZoom.value ? { '--dag-zoom': String(zoomFactor.value) } : undefined))
const iconSize = computed(() => Math.max(10, Math.round(14 * zoomFactor.value)))
const edgeW = computed(() => 28 * zoomFactor.value)
const edgeH = computed(() => 12 * zoomFactor.value)

function statusClass(status: DagNodeView['status']): string {
  return `is-${status}`
}

function onSelect(node: DagNodeView, e: MouseEvent) {
  emit('select', node)
  if (e.detail > 0) {
    (e.currentTarget as HTMLButtonElement).blur()
  }
}
</script>

<template>
  <div
    v-if="visibleNodes.length"
    class="plan-dag"
    :class="{ 'is-fluid': fluid, 'has-zoom': hasZoom }"
    :style="rootStyle"
  >
    <div class="plan-dag-body">
      <div class="plan-dag-scroll">
        <div class="plan-dag-track">
      <template v-for="(node, idx) in visibleNodes" :key="node.id">
        <button
          type="button"
          class="plan-dag-node"
          :class="[statusClass(node.status), { 'is-selected': selectedId === node.id, 'is-live': live && node.status === 'running', 'is-terminal': node.type === 'start' || node.type === 'answer' }]"
          :title="node.label"
          @click="onSelect(node, $event)"
        >
          <span class="node-icon" aria-hidden="true">
            <PlanNodeIcon :type="node.type" :size="iconSize" />
          </span>
          <span class="node-label">{{ node.label }}</span>
          <span v-if="node.attemptCount != null && node.attemptCount > 1" class="node-attempt">×{{ node.attemptCount }}</span>
          <span v-if="node.durationMs != null" class="node-dur">{{ formatDuration(node.durationMs) }}</span>
          <span v-else-if="live && node.status === 'running'" class="node-dur node-dur-live">进行中</span>
          <span v-else class="node-dur node-dur-placeholder" aria-hidden="true">&nbsp;</span>
        </button>
        <div v-if="idx < visibleNodes.length - 1" class="plan-dag-edge" aria-hidden="true">
          <svg :width="edgeW" :height="edgeH" viewBox="0 0 28 12" fill="none">
            <path d="M0 6h20" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
            <path d="M20 6l-4-3.5M20 6l-4 3.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
      </template>
        </div>
      </div>
    </div>
    <div v-if="showExpand && !fluid" class="plan-dag-aside">
      <button
        type="button"
        class="plan-dag-expand-btn"
        title="放大查看"
        aria-label="放大执行图"
        @click.stop="emit('expand')"
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3" />
        </svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.plan-dag {
  position: relative;
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  margin: 8px 0 4px calc(var(--op-gutter, 12px) + 4px);
  padding: 12px 10px 12px 14px;
  min-height: 94px;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: color-mix(in srgb, var(--sun-bg) 92%, var(--sun-text-muted));
  overflow: visible;
}

.plan-dag-body {
  flex: 1;
  min-width: 0;
  min-height: 70px;
  display: flex;
  flex-direction: column;
  overflow: visible;
}

.plan-dag-scroll {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 12px 12px 10px;
  box-sizing: border-box;
}

.plan-dag-aside {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  width: 36px;
  margin-left: 6px;
}

.plan-dag.is-fluid {
  margin: 0;
  padding: 16px 20px;
  min-height: 0;
  overflow: visible;
}

.plan-dag.is-fluid .plan-dag-body {
  overflow: visible;
  min-height: 0;
}

.plan-dag.is-fluid .plan-dag-scroll {
  overflow: visible;
  padding-bottom: 0;
}

.plan-dag.has-zoom {
  --z: var(--dag-zoom, 1);
}

.plan-dag.has-zoom.is-fluid {
  padding: calc(16px * var(--z)) calc(20px * var(--z));
  min-height: calc(120px * var(--z));
}

.plan-dag.has-zoom .plan-dag-node {
  width: calc(112px * var(--z));
  height: calc(70px * var(--z));
  gap: calc(4px * var(--z));
  padding: calc(8px * var(--z)) calc(10px * var(--z)) calc(6px * var(--z));
  border-radius: calc(8px * var(--z));
}

.plan-dag.has-zoom.is-fluid .plan-dag-node {
  width: auto;
  height: auto;
  min-width: calc(112px * var(--z));
  min-height: calc(70px * var(--z));
  padding: calc(10px * var(--z)) calc(14px * var(--z)) calc(8px * var(--z));
}

.plan-dag.has-zoom .plan-dag-node.is-terminal {
  width: calc(84px * var(--z));
}

.plan-dag.has-zoom.is-fluid .plan-dag-node.is-terminal {
  width: auto;
  min-width: calc(84px * var(--z));
}

.plan-dag.has-zoom .node-icon {
  width: calc(18px * var(--z));
  height: calc(18px * var(--z));
}

.plan-dag.has-zoom .node-label {
  font-size: calc(12px * var(--z));
  line-height: 1.2;
}

.plan-dag.has-zoom .node-dur {
  min-height: calc(14px * var(--z));
  font-size: calc(10px * var(--z));
  line-height: calc(14px * var(--z));
}

.plan-dag.has-zoom .plan-dag-edge {
  padding: 0 calc(2px * var(--z));
}

.plan-dag-track {
  display: flex;
  align-items: stretch;
  gap: 0;
  min-width: min-content;
  flex-shrink: 0;
}

.plan-dag-expand-btn {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--sun-border);
  border-radius: 6px;
  background: color-mix(in srgb, var(--sun-bg) 88%, var(--sun-text-muted));
  color: var(--sun-text-muted);
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.plan-dag-expand-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
  border-color: var(--sun-border-light);
}

.plan-dag-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 4px;
  box-sizing: border-box;
  width: 112px;
  height: 70px;
  flex-shrink: 0;
  padding: 8px 10px 6px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-bg);
  color: var(--sun-text-secondary);
  font: inherit;
  text-align: center;
  cursor: pointer;
  outline: none;
  -webkit-tap-highlight-color: transparent;
  transition: border-color 0.15s, box-shadow 0.15s, color 0.15s;
}

.plan-dag-node:focus,
.plan-dag-node:active {
  outline: none;
}

.plan-dag-node:focus-visible:not(.is-selected) {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-blue, #58a6ff) 45%, transparent);
}

.plan-dag-node:hover:not(.is-selected) {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

/* 选中仅加外圈，不覆盖 done/running/error 的状态色 */
.plan-dag-node.is-selected {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
}

.plan-dag-node.is-selected.is-running,
.plan-dag-node.is-selected.is-live {
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 40%, transparent),
    0 0 0 3px color-mix(in srgb, var(--sun-blue, #58a6ff) 35%, transparent);
}

.plan-dag-node.is-selected.is-done {
  box-shadow: 0 0 0 2px color-mix(in srgb, #4ade80 50%, transparent);
}

.plan-dag-node.is-selected.is-error {
  box-shadow: 0 0 0 2px color-mix(in srgb, #f87171 50%, transparent);
}

.plan-dag-node.is-running,
.plan-dag-node.is-live {
  border-color: var(--sun-blue, #58a6ff);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 40%, transparent);
}

.plan-dag.has-zoom .plan-dag-node.is-live,
.plan-dag.has-zoom .plan-dag-node.is-selected.is-live {
  animation: none;
  transition: none;
}

.plan-dag-node.is-live {
  transition: color 0.15s;
  animation: dag-node-breathe 2.2s ease-in-out infinite;
}

.plan-dag-node.is-selected.is-live {
  animation: dag-node-breathe-selected 2.2s ease-in-out infinite;
}

@media (prefers-reduced-motion: reduce) {
  .plan-dag-node.is-live,
  .plan-dag-node.is-selected.is-live {
    animation: none;
  }
}

.plan-dag-node.is-done {
  border-color: color-mix(in srgb, #4ade80 35%, var(--sun-border));
}

.plan-dag-node.is-terminal {
  width: 84px;
}

.plan-dag.is-fluid .plan-dag-node {
  width: auto;
  min-width: 112px;
  max-width: none;
  height: auto;
  min-height: 70px;
  padding: 10px 14px 8px;
}

.plan-dag.is-fluid .plan-dag-node.is-terminal {
  width: auto;
  min-width: 84px;
}

.plan-dag.is-fluid .node-label {
  white-space: nowrap;
  overflow: visible;
  text-overflow: clip;
}

.plan-dag-node.is-terminal:not(.is-done):not(.is-running):not(.is-error) {
  opacity: 0.88;
  background: color-mix(in srgb, var(--sun-bg) 96%, var(--sun-text-muted));
}

.plan-dag-node.is-terminal.is-done .node-label {
  color: var(--sun-text);
}

.plan-dag-node.is-error {
  border-color: color-mix(in srgb, #f87171 45%, var(--sun-border));
}

.node-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  opacity: 0.92;
}

.plan-dag-node.is-running .node-icon,
.plan-dag-node.is-live .node-icon {
  opacity: 1;
  color: var(--sun-blue, #58a6ff);
}

.plan-dag-node.is-running .node-label,
.plan-dag-node.is-live .node-label {
  color: var(--sun-blue, #58a6ff);
}

.plan-dag-node.is-running .node-dur-live,
.plan-dag-node.is-live .node-dur-live {
  color: var(--sun-blue, #58a6ff);
  opacity: 1;
}

.node-label {
  width: 100%;
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-dur {
  flex-shrink: 0;
  width: 100%;
  min-height: 14px;
  font-size: 10px;
  line-height: 14px;
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-dur-placeholder {
  visibility: hidden;
}

.node-dur-live {
  opacity: 0.75;
}

.plan-dag-edge {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  align-self: center;
  color: var(--sun-text-muted);
  opacity: 0.45;
  padding: 0 2px;
}

@keyframes dag-node-breathe {
  0%, 100% {
    border-color: color-mix(in srgb, var(--sun-blue, #58a6ff) 72%, transparent);
    box-shadow: 0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 28%, transparent);
  }
  50% {
    border-color: var(--sun-blue, #58a6ff);
    box-shadow:
      0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 52%, transparent),
      0 0 10px color-mix(in srgb, var(--sun-blue, #58a6ff) 22%, transparent);
  }
}

@keyframes dag-node-breathe-selected {
  0%, 100% {
    border-color: color-mix(in srgb, var(--sun-blue, #58a6ff) 72%, transparent);
    box-shadow:
      0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 28%, transparent),
      0 0 0 3px color-mix(in srgb, var(--sun-blue, #58a6ff) 22%, transparent);
  }
  50% {
    border-color: var(--sun-blue, #58a6ff);
    box-shadow:
      0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 48%, transparent),
      0 0 0 3px color-mix(in srgb, var(--sun-blue, #58a6ff) 42%, transparent),
      0 0 12px color-mix(in srgb, var(--sun-blue, #58a6ff) 20%, transparent);
  }
}
</style>
