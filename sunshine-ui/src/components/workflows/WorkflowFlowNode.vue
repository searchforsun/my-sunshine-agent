<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import PlanNodeIcon from '../plan/PlanNodeIcon.vue'
import type { WorkflowFlowNodeData } from '../../utils/workflowDagLayout'

const props = defineProps<{
  data: WorkflowFlowNodeData
  selected?: boolean
}>()

const nodeType = computed(() => props.data.nodeType)
const isStart = computed(() => nodeType.value === 'start')
const isAnswer = computed(() => nodeType.value === 'answer')
const isJoin = computed(() => nodeType.value === 'join')
const showTarget = computed(() => !isStart.value)
const showSource = computed(() => !isAnswer.value)
const showForkBadge = computed(() => (props.data.forkOutCount ?? 0) >= 2)
</script>

<template>
  <div
    class="wf-flow-node"
    :class="[
      `is-${nodeType}`,
      {
        'is-selected': selected || data.selected,
        'is-readonly': data.readOnly,
        'is-join': isJoin,
        'has-issue': data.hasValidationIssue,
      },
    ]"
  >
    <span v-if="showForkBadge" class="wf-fork-badge">分叉</span>
    <Handle
      v-if="showTarget"
      type="target"
      :position="Position.Left"
      :connectable="!data.readOnly"
      class="wf-flow-handle"
    />
    <span class="wf-flow-icon" aria-hidden="true">
      <PlanNodeIcon :type="nodeType" :size="16" />
    </span>
    <span class="wf-flow-label">{{ data.label }}</span>
    <Handle
      v-if="showSource"
      type="source"
      :position="Position.Right"
      :connectable="!data.readOnly"
      class="wf-flow-handle"
    />
  </div>
</template>

<style scoped>
.wf-flow-node {
  position: relative;
  box-sizing: border-box;
  min-width: 148px;
  max-width: 168px;
  min-height: 56px;
  padding: 10px 14px 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  text-align: center;
  cursor: grab;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.wf-flow-node:active {
  cursor: grabbing;
}

.wf-flow-node.is-selected {
  border-color: var(--sun-border-light);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
}

.wf-flow-node.is-readonly {
  cursor: pointer;
}

.wf-flow-node.is-start,
.wf-flow-node.is-answer {
  border-style: dashed;
  opacity: 0.92;
}

.wf-flow-node.is-join {
  border-color: color-mix(in srgb, var(--sun-border-light) 70%, var(--sun-blue, #58a6ff));
}

.wf-flow-node.has-issue {
  border-color: var(--sun-amber);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--sun-amber) 45%, transparent);
}

.wf-fork-badge {
  position: absolute;
  top: -6px;
  left: -4px;
  padding: 0 4px;
  font-size: 10px;
  line-height: 16px;
  border: 1px solid var(--sun-border);
  border-radius: 4px;
  background: var(--sun-black);
  color: var(--sun-blue, #58a6ff);
}

.wf-flow-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.9;
}

.wf-flow-label {
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  color: var(--sun-text);
  line-height: 1.25;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wf-flow-handle {
  width: 8px;
  height: 8px;
  background: var(--sun-black);
  border: 1.5px solid var(--sun-border-light);
}

.wf-flow-handle:hover {
  border-color: var(--sun-blue, #58a6ff);
}
</style>

<style>
/* Vue Flow 选中态 */
.vue-flow__node.selected .wf-flow-node {
  border-color: var(--sun-border-light);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
}
</style>
