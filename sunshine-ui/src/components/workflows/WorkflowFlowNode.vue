<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import PlanNodeIcon from '../plan/PlanNodeIcon.vue'
import { isGatewayType } from '../../utils/workflowGateway'
import type { WorkflowFlowNodeData } from '../../utils/workflowDagLayout'

const props = defineProps<{
  data: WorkflowFlowNodeData
  selected?: boolean
}>()

const nodeType = computed(() => props.data.nodeType)
const isStart = computed(() => nodeType.value === 'start')
const isAnswer = computed(() => nodeType.value === 'answer')
const isGateway = computed(() => isGatewayType(nodeType.value))
const showTarget = computed(() => !isStart.value)
const showSource = computed(() => !isAnswer.value)
</script>

<template>
  <div
    class="wf-flow-node-wrap"
    :class="{ 'is-gateway-wrap': isGateway }"
  >
    <div
      v-if="!isGateway"
      class="wf-flow-node"
      :class="[
        `is-${nodeType}`,
        {
          'is-selected': selected || data.selected,
          'is-readonly': data.readOnly,
          'has-issue': data.hasValidationIssue,
        },
      ]"
    >
      <Handle
        v-if="showTarget"
        type="target"
        :position="Position.Left"
        :connectable="!data.readOnly"
        class="wf-flow-handle"
      />
      <div class="wf-flow-body">
        <span class="wf-flow-icon" aria-hidden="true">
          <PlanNodeIcon :type="nodeType" :size="16" />
        </span>
        <span class="wf-flow-label">{{ data.label }}</span>
      </div>
      <Handle
        v-if="showSource"
        type="source"
        :position="Position.Right"
        :connectable="!data.readOnly"
        class="wf-flow-handle"
      />
    </div>

    <template v-else>
      <div
        class="wf-gateway-shell"
        :class="[
          `is-${nodeType}`,
          {
            'is-selected': selected || data.selected,
            'is-readonly': data.readOnly,
            'has-issue': data.hasValidationIssue,
          },
        ]"
      >
        <Handle
          v-if="showTarget"
          type="target"
          :position="Position.Left"
          :connectable="!data.readOnly"
          class="wf-flow-handle wf-gateway-handle"
        />
        <div class="wf-gateway-diamond" aria-hidden="true">
          <span class="wf-gateway-symbol">
            <PlanNodeIcon :type="nodeType" :size="14" symbol-only />
          </span>
        </div>
        <Handle
          v-if="showSource"
          type="source"
          :position="Position.Right"
          :connectable="!data.readOnly"
          class="wf-flow-handle wf-gateway-handle"
        />
      </div>
      <span class="wf-gateway-caption">{{ data.label }}</span>
    </template>
  </div>
</template>

<style scoped>
.wf-flow-node-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.wf-flow-node-wrap.is-gateway-wrap {
  position: relative;
  /* caption 不参与 Vue Flow 节点高度测量，避免主干连线垂直错位 */
  height: 40px;
  overflow: visible;
}

.wf-flow-node {
  position: relative;
  box-sizing: border-box;
  min-width: 148px;
  max-width: 168px;
  height: 56px;
  min-height: 56px;
  padding: 10px 14px 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
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

.wf-flow-node.has-issue {
  border-color: var(--sun-amber);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--sun-amber) 45%, transparent);
}

.wf-gateway-shell {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  cursor: grab;
}

.wf-gateway-shell:active {
  cursor: grabbing;
}

.wf-gateway-shell.is-readonly {
  cursor: pointer;
}

.wf-gateway-diamond {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  transform: rotate(45deg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.wf-gateway-shell.is-join .wf-gateway-diamond,
.wf-gateway-shell.is-parallel-gateway .wf-gateway-diamond {
  border-color: color-mix(in srgb, var(--sun-border-light) 70%, var(--sun-blue, #58a6ff));
}

.wf-gateway-shell.is-exclusive-gateway .wf-gateway-diamond {
  border-color: color-mix(in srgb, var(--sun-border-light) 70%, var(--sun-amber, #c9a227));
}

.wf-gateway-shell.is-selected .wf-gateway-diamond {
  border-color: var(--sun-border-light);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
}

.wf-gateway-shell.has-issue .wf-gateway-diamond {
  border-color: var(--sun-amber);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--sun-amber) 45%, transparent);
}

.wf-gateway-symbol {
  display: flex;
  align-items: center;
  justify-content: center;
  transform: rotate(-45deg);
  color: var(--sun-text-secondary);
}

.wf-flow-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 0;
}

.wf-flow-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.95;
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
  text-align: center;
}

.wf-gateway-caption {
  position: absolute;
  top: 42px;
  left: 50%;
  transform: translateX(-50%);
  width: 88px;
  font-size: 11px;
  font-weight: 500;
  color: var(--sun-text-secondary);
  line-height: 1.25;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  pointer-events: none;
}

.wf-flow-handle {
  width: 8px;
  height: 8px;
  background: var(--sun-black);
  border: 1.5px solid var(--sun-border-light);
}

.wf-gateway-handle {
  top: 50%;
  transform: translateY(-50%);
}

.wf-flow-handle:hover {
  border-color: var(--sun-blue, #58a6ff);
}
</style>

<style>
.vue-flow__node.selected .wf-flow-node,
.vue-flow__node.selected .wf-gateway-shell.is-selected .wf-gateway-diamond,
.vue-flow__node.selected .wf-gateway-diamond {
  border-color: var(--sun-border-light);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
}
</style>
