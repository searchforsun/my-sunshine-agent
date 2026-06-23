<script setup lang="ts">
import { computed } from 'vue'
import { formatDuration } from '../../api/processingSteps'
import PlanNodeIcon from './PlanNodeIcon.vue'
import type { DagNodeView } from '../../utils/planGraph'

const props = defineProps<{
  nodes: DagNodeView[]
  selectedId?: string
  live?: boolean
}>()

const emit = defineEmits<{
  select: [node: DagNodeView]
}>()

const visibleNodes = computed(() => props.nodes.filter(n => n.label))

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
  <div v-if="visibleNodes.length" class="plan-dag">
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
            <PlanNodeIcon :type="node.type" :size="14" />
          </span>
          <span class="node-label">{{ node.label }}</span>
          <span v-if="node.durationMs != null" class="node-dur">{{ formatDuration(node.durationMs) }}</span>
          <span v-else-if="live && node.status === 'running'" class="node-dur node-dur-live">进行中</span>
          <span v-else class="node-dur node-dur-placeholder" aria-hidden="true">&nbsp;</span>
        </button>
        <div v-if="idx < visibleNodes.length - 1" class="plan-dag-edge" aria-hidden="true">
          <svg width="28" height="12" viewBox="0 0 28 12" fill="none">
            <path d="M0 6h20" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
            <path d="M20 6l-4-3.5M20 6l-4 3.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.plan-dag {
  margin: 8px 0 4px calc(var(--op-gutter, 12px) + 4px);
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: color-mix(in srgb, var(--sun-bg) 92%, var(--sun-text-muted));
  overflow-x: auto;
}

.plan-dag-track {
  display: flex;
  align-items: stretch;
  gap: 0;
  min-width: min-content;
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

.plan-dag-node.is-done {
  border-color: color-mix(in srgb, #4ade80 35%, var(--sun-border));
}

.plan-dag-node.is-terminal {
  width: 84px;
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
</style>
