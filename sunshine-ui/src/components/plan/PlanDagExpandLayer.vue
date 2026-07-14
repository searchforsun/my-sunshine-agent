<script setup lang="ts">
import PlanExecutionCanvas from './PlanExecutionCanvas.vue'
import type { PlanGraph } from '../../api/executionPlans'
import type { DagNodeView } from '../../utils/planGraph'

defineProps<{
  graph: PlanGraph
  nodes: DagNodeView[]
  selectedId?: string
  live?: boolean
  title?: string
  userQuery?: string
  loadingLabel?: string
}>()

const emit = defineEmits<{
  close: []
  select: [node: DagNodeView]
}>()
</script>

<template>
  <div class="plan-dag-expand-layer">
    <header class="plan-dag-toolbar">
      <h3 class="toolbar-title">{{ title || '执行图' }}</h3>
      <p v-if="userQuery" class="toolbar-user-query" :title="userQuery">{{ userQuery }}</p>
      <div class="toolbar-actions">
        <button type="button" class="dag-toolbar-btn" title="退出放大" aria-label="退出放大" @click="emit('close')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
        </button>
      </div>
    </header>
    <PlanExecutionCanvas
      :graph="graph"
      :dag-nodes="nodes"
      :selected-id="selectedId"
      :live="live"
      :loading-label="loadingLabel"
      fluid
      @select="emit('select', $event)"
    />
  </div>
</template>

<style scoped>
.plan-dag-expand-layer {
  position: absolute;
  inset: 0;
  z-index: 50;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: var(--sun-black);
  isolation: isolate;
}

.plan-dag-toolbar {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.toolbar-title {
  margin: 0;
  font-size: var(--sun-font-md);
  font-weight: 600;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.toolbar-user-query {
  margin: 0;
  min-width: 0;
  padding: 0 8px;
  font-size: var(--sun-font-base);
  font-weight: 450;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dag-toolbar-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--sun-border);
  border-radius: 6px;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  cursor: pointer;
}

.dag-toolbar-btn:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}
</style>
