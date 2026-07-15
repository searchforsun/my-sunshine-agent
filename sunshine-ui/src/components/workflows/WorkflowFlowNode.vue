<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { NodeResizer } from '@vue-flow/node-resizer'
import '@vue-flow/node-resizer/dist/style.css'
import PlanNodeIcon from '../plan/PlanNodeIcon.vue'
import { isGatewayType, isLoopType } from '../../utils/workflowGateway'
import type { WorkflowFlowNodeData } from '../../utils/workflowFlowProjection'
import { resolveExecStatusText, resolveExecVisualClasses } from '../../utils/workflowFlowNodeVisual'

const props = defineProps<{
  data: WorkflowFlowNodeData
  selected?: boolean
}>()

const nodeType = computed(() => props.data.nodeType)
const isStart = computed(() => nodeType.value === 'start')
const isAnswer = computed(() => nodeType.value === 'answer')
const isGateway = computed(() => isGatewayType(nodeType.value))
const isLoop = computed(() => isLoopType(nodeType.value))
const showTarget = computed(() => !isStart.value)
const showSource = computed(() => !isAnswer.value)
const exec = computed(() => props.data.exec)
const showLoopResizer = computed(
  () => isLoop.value && (props.selected || props.data.selected) && !props.data.readOnly,
)

const execStatusText = computed(() => resolveExecStatusText(exec.value, nodeType.value))
const execVisualClasses = computed(() => resolveExecVisualClasses(exec.value))
</script>

<template>
  <div
    class="wf-flow-node-wrap"
    :class="{ 'is-gateway-wrap': isGateway, 'is-loop-wrap': isLoop }"
  >
    <div
      v-if="isLoop"
      class="wf-loop-shell"
      :class="[
        execVisualClasses,
        {
          'is-selected': selected || data.selected,
          'is-readonly': data.readOnly,
          'has-issue': data.hasValidationIssue,
        },
      ]"
    >
      <NodeResizer
        v-if="showLoopResizer"
        :min-width="280"
        :min-height="160"
        color="var(--sun-text-muted)"
      />
      <Handle
        v-if="showTarget"
        type="target"
        :position="Position.Left"
        :connectable="!data.readOnly"
        class="wf-flow-handle"
      />
      <div class="wf-loop-header">
        <span class="wf-flow-icon" aria-hidden="true">
          <PlanNodeIcon type="loop" :size="16" />
        </span>
        <span class="wf-flow-label">{{ data.label }}</span>
        <span
          v-if="execStatusText"
          class="wf-flow-exec-status"
          :class="exec?.status ? `is-${exec.status}` : undefined"
        >{{ execStatusText }}</span>
      </div>
      <Handle
        v-if="showSource"
        type="source"
        :position="Position.Right"
        :connectable="!data.readOnly"
        class="wf-flow-handle"
      />
    </div>

    <div
      v-else-if="!isGateway"
      class="wf-flow-node"
      :class="[
        `is-${nodeType}`,
        execVisualClasses,
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
        <span
          v-if="execStatusText"
          class="wf-flow-exec-status"
          :class="exec?.status ? `is-${exec.status}` : undefined"
        >{{ execStatusText }}</span>
      </div>
      <span v-if="exec?.retryBadge" class="wf-flow-retry-badge">×{{ exec.retryBadge }}</span>
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
          execVisualClasses,
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
      <span
        v-if="execStatusText"
        class="wf-gateway-exec-status"
        :class="exec?.status ? `is-${exec.status}` : undefined"
      >{{ execStatusText }}</span>
      <span v-if="exec?.retryBadge" class="wf-flow-retry-badge wf-gateway-retry-badge">×{{ exec.retryBadge }}</span>
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

.wf-flow-node-wrap.is-loop-wrap {
  width: 100%;
  height: 100%;
}

.wf-loop-shell {
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-width: 280px;
  min-height: 160px;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: transparent;
  position: relative;
  padding: 8px 10px;
}

.wf-loop-shell.is-selected {
  border-color: var(--sun-text-secondary);
}

.wf-loop-header {
  display: flex;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}

.wf-flow-node {
  position: relative;
  box-sizing: border-box;
  min-width: 148px;
  max-width: 168px;
  min-height: 56px;
  height: 56px;
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

/* 开始/回答：紧凑方卡 + 四边等宽实线（非业务节点长条） */
.wf-flow-node.is-start,
.wf-flow-node.is-answer {
  box-sizing: border-box;
  width: 56px;
  min-width: 56px;
  max-width: 56px;
  min-height: 56px;
  height: 56px;
  padding: 6px 4px;
  border-style: solid;
  opacity: 1;
}

.wf-flow-node.is-start .wf-flow-label,
.wf-flow-node.is-answer .wf-flow-label {
  max-width: 48px;
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

.wf-flow-exec-status,
.wf-gateway-exec-status {
  position: absolute;
  top: calc(100% + 4px);
  left: 50%;
  transform: translateX(-50%);
  width: 88px;
  font-size: 10px;
  line-height: 1.2;
  color: var(--sun-text-muted);
  white-space: nowrap;
  text-align: center;
  pointer-events: none;
}

.wf-gateway-exec-status {
  top: 58px;
  width: 72px;
}

.wf-flow-exec-status.is-running,
.wf-gateway-exec-status.is-running {
  color: var(--sun-blue, #58a6ff);
}

.wf-flow-exec-status.is-error,
.wf-gateway-exec-status.is-error {
  color: var(--sun-amber, #c9a227);
}

.wf-flow-exec-status.is-awaiting_confirm,
.wf-gateway-exec-status.is-awaiting_confirm {
  color: var(--sun-amber, #c9a227);
}

.wf-flow-retry-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  font-size: 10px;
  line-height: 14px;
  color: var(--sun-text-muted);
  pointer-events: none;
}

.wf-gateway-retry-badge {
  top: -2px;
  right: -10px;
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

/* —— 执行态：与 PlanExecutionCanvas 对齐的状态色（2px 边框 + 同色系浅底） —— */
.wf-flow-node.has-exec-state,
.wf-gateway-shell.has-exec-state .wf-gateway-diamond {
  border-width: 2px;
  transition: border-color 0.15s, box-shadow 0.15s, background 0.15s, color 0.15s;
}

.wf-flow-node.has-exec-state.is-running,
.wf-flow-node.has-exec-state.is-live,
.wf-gateway-shell.has-exec-state.is-running .wf-gateway-diamond,
.wf-gateway-shell.has-exec-state.is-live .wf-gateway-diamond {
  border-color: var(--sun-blue, #58a6ff);
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 12%, var(--sun-black));
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 35%, transparent);
}

.wf-flow-node.has-exec-state.is-live {
  animation: wf-exec-breathe 2.2s ease-in-out infinite;
}

.wf-gateway-shell.has-exec-state.is-live .wf-gateway-diamond {
  animation: wf-exec-breathe 2.2s ease-in-out infinite;
}

.wf-flow-node.has-exec-state.is-done,
.wf-gateway-shell.has-exec-state.is-done .wf-gateway-diamond {
  border-color: color-mix(in srgb, #4ade80 55%, var(--sun-border));
  background: color-mix(in srgb, #4ade80 10%, var(--sun-black));
}

.wf-flow-node.has-exec-state.is-error,
.wf-gateway-shell.has-exec-state.is-error .wf-gateway-diamond {
  border-color: color-mix(in srgb, #f87171 55%, var(--sun-border));
  background: color-mix(in srgb, #f87171 10%, var(--sun-black));
}

.wf-flow-node.has-exec-state.is-error.is-live-recovery,
.wf-gateway-shell.has-exec-state.is-error.is-live-recovery .wf-gateway-diamond {
  border-color: color-mix(in srgb, #f87171 58%, var(--sun-border));
  background: color-mix(in srgb, #f87171 12%, var(--sun-black));
  box-shadow: 0 0 0 1px color-mix(in srgb, #f87171 28%, transparent);
  animation: wf-exec-breathe-error 2.2s ease-in-out infinite;
}

.wf-flow-node.has-exec-state.is-pending,
.wf-gateway-shell.has-exec-state.is-pending .wf-gateway-diamond {
  border-style: dashed;
  border-color: color-mix(in srgb, #d4d4d8 80%, var(--sun-border));
  background: color-mix(in srgb, #e4e4e7 8%, var(--sun-black));
}

.wf-flow-node.is-start.has-exec-state,
.wf-flow-node.is-answer.has-exec-state {
  border-style: solid;
  border-width: 2px;
}

.wf-flow-node.has-exec-state.is-skipped,
.wf-gateway-shell.has-exec-state.is-skipped .wf-gateway-diamond {
  border-style: solid;
  border-color: color-mix(in srgb, #64748b 58%, var(--sun-border));
  background: color-mix(in srgb, #64748b 10%, var(--sun-black));
}

.wf-flow-node.has-exec-state.is-terminated,
.wf-gateway-shell.has-exec-state.is-terminated .wf-gateway-diamond {
  border-color: color-mix(in srgb, #be123c 48%, var(--sun-border));
  background: color-mix(in srgb, #be123c 9%, var(--sun-black));
}

.wf-flow-node.has-exec-state.is-awaiting-confirm,
.wf-gateway-shell.has-exec-state.is-awaiting-confirm .wf-gateway-diamond {
  border-color: color-mix(in srgb, #a855f7 58%, var(--sun-border));
  background: color-mix(in srgb, #a855f7 10%, var(--sun-black));
}

.wf-flow-node.has-exec-state.is-awaiting-breathe,
.wf-gateway-shell.has-exec-state.is-awaiting-breathe .wf-gateway-diamond {
  box-shadow: 0 0 0 1px color-mix(in srgb, #a855f7 28%, transparent);
  animation: wf-exec-breathe-awaiting 2.2s ease-in-out infinite;
}

.wf-flow-node.has-exec-state.is-paused,
.wf-gateway-shell.has-exec-state.is-paused .wf-gateway-diamond {
  border-color: color-mix(in srgb, #fbbf24 58%, var(--sun-border));
  background: color-mix(in srgb, #fbbf24 10%, var(--sun-black));
}

.wf-flow-node.has-exec-state.is-paused-breathe,
.wf-gateway-shell.has-exec-state.is-paused-breathe .wf-gateway-diamond {
  box-shadow: 0 0 0 1px color-mix(in srgb, #fbbf24 28%, transparent);
  animation: wf-exec-breathe-paused 2.2s ease-in-out infinite;
}

.wf-flow-node.has-exec-state.is-running .wf-flow-icon,
.wf-flow-node.has-exec-state.is-running .wf-flow-label,
.wf-flow-node.has-exec-state.is-live .wf-flow-icon,
.wf-flow-node.has-exec-state.is-live .wf-flow-label {
  color: var(--sun-blue, #58a6ff);
}

.wf-flow-node.has-exec-state.is-pending .wf-flow-icon,
.wf-flow-node.has-exec-state.is-pending .wf-flow-label {
  color: #a1a1aa;
}

.wf-flow-node.has-exec-state.is-skipped .wf-flow-icon,
.wf-flow-node.has-exec-state.is-skipped .wf-flow-label {
  color: #64748b;
}

.wf-gateway-shell.has-exec-state.is-skipped .wf-gateway-symbol {
  color: #64748b;
}

.wf-flow-node.has-exec-state.is-terminated .wf-flow-icon,
.wf-flow-node.has-exec-state.is-terminated .wf-flow-label {
  color: #9f1239;
}

.wf-flow-node.has-exec-state.is-awaiting-confirm .wf-flow-icon,
.wf-flow-node.has-exec-state.is-awaiting-confirm .wf-flow-label,
.wf-flow-node.has-exec-state.is-awaiting-breathe .wf-flow-icon,
.wf-flow-node.has-exec-state.is-awaiting-breathe .wf-flow-label {
  color: #9333ea;
}

.wf-flow-node.has-exec-state.is-paused .wf-flow-icon,
.wf-flow-node.has-exec-state.is-paused .wf-flow-label,
.wf-flow-node.has-exec-state.is-paused-breathe .wf-flow-icon,
.wf-flow-node.has-exec-state.is-paused-breathe .wf-flow-label {
  color: #d97706;
}

.wf-flow-node.has-exec-state.is-error.is-live-recovery .wf-flow-icon,
.wf-flow-node.has-exec-state.is-error.is-live-recovery .wf-flow-label {
  color: #f87171;
}

.wf-gateway-shell.has-exec-state.is-running .wf-gateway-symbol,
.wf-gateway-shell.has-exec-state.is-live .wf-gateway-symbol {
  color: var(--sun-blue, #58a6ff);
}

@media (prefers-reduced-motion: reduce) {
  .wf-flow-node.has-exec-state.is-live,
  .wf-flow-node.has-exec-state.is-awaiting-breathe,
  .wf-flow-node.has-exec-state.is-paused-breathe,
  .wf-flow-node.has-exec-state.is-error.is-live-recovery,
  .wf-gateway-shell.has-exec-state.is-live .wf-gateway-diamond,
  .wf-gateway-shell.has-exec-state.is-awaiting-breathe .wf-gateway-diamond,
  .wf-gateway-shell.has-exec-state.is-paused-breathe .wf-gateway-diamond,
  .wf-gateway-shell.has-exec-state.is-error.is-live-recovery .wf-gateway-diamond {
    animation: none;
  }
}

@keyframes wf-exec-breathe {
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

@keyframes wf-exec-breathe-awaiting {
  0%, 100% {
    border-color: color-mix(in srgb, #a855f7 55%, transparent);
    box-shadow: 0 0 0 1px color-mix(in srgb, #a855f7 22%, transparent);
  }
  50% {
    border-color: #a855f7;
    box-shadow:
      0 0 0 1px color-mix(in srgb, #a855f7 42%, transparent),
      0 0 10px color-mix(in srgb, #a855f7 18%, transparent);
  }
}

@keyframes wf-exec-breathe-paused {
  0%, 100% {
    border-color: color-mix(in srgb, #fbbf24 55%, transparent);
    box-shadow: 0 0 0 1px color-mix(in srgb, #fbbf24 22%, transparent);
  }
  50% {
    border-color: #fbbf24;
    box-shadow:
      0 0 0 1px color-mix(in srgb, #fbbf24 42%, transparent),
      0 0 10px color-mix(in srgb, #fbbf24 18%, transparent);
  }
}

@keyframes wf-exec-breathe-error {
  0%, 100% {
    border-color: color-mix(in srgb, #f87171 55%, transparent);
    box-shadow: 0 0 0 1px color-mix(in srgb, #f87171 22%, transparent);
  }
  50% {
    border-color: #f87171;
    box-shadow:
      0 0 0 1px color-mix(in srgb, #f87171 42%, transparent),
      0 0 10px color-mix(in srgb, #f87171 18%, transparent);
  }
}
</style>

<style>
.vue-flow__node.selected .wf-flow-node:not(.has-exec-state),
.vue-flow__node.selected .wf-gateway-shell:not(.has-exec-state) .wf-gateway-diamond,
.vue-flow__node.selected .wf-gateway-shell:not(.has-exec-state).is-selected .wf-gateway-diamond {
  border-color: var(--sun-border-light);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-muted) 35%, transparent);
}

.vue-flow__node.selected .wf-flow-node.has-exec-state.is-selected,
.vue-flow__node.selected .wf-flow-node.has-exec-state.is-running,
.vue-flow__node.selected .wf-flow-node.has-exec-state.is-live {
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--sun-blue, #58a6ff) 40%, transparent),
    0 0 0 3px color-mix(in srgb, var(--sun-blue, #58a6ff) 35%, transparent);
}

.vue-flow__node.selected .wf-flow-node.has-exec-state.is-done {
  box-shadow: 0 0 0 2px color-mix(in srgb, #4ade80 50%, transparent);
}

.vue-flow__node.selected .wf-flow-node.has-exec-state.is-error {
  box-shadow: 0 0 0 2px color-mix(in srgb, #f87171 50%, transparent);
}

.vue-flow__node.selected .wf-flow-node.has-exec-state.is-skipped {
  box-shadow: 0 0 0 2px color-mix(in srgb, #64748b 50%, transparent);
}
</style>
