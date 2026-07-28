<script setup lang="ts">
import { computed, inject } from 'vue'
import WorkflowNodeConfigSection from './WorkflowNodeConfigSection.vue'
import ConditionGroupEditor from './ConditionGroupEditor.vue'
import { formatPlanNodeType } from '../../api/executionPlans'
import { workflowNodeFieldHelp } from './workflowFieldHelp'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import { countNodeDegree } from '../../utils/workflowPlanValidation'
import { normalizeEdgeConditionGroup, exclusiveGatewayConditionLeft } from '../../utils/workflowPlan'
import { upstreamNodesOf } from '../../utils/workflowVariableRefs'
import type { WorkflowPlanEdgeConditionGroup } from '../../api/workflows'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi
const readOnly = computed(() => !page.canEditPlan)

const gatewayTopology = computed(() => {
  const node = page.selectedNode
  if (!node || node.type !== 'exclusive-gateway' || !page.plan) return null
  const degree = countNodeDegree(page.plan, node.id)
  return { ...degree, okOut: degree.out >= 2 }
})

const exclusiveOutEdges = computed(() => {
  const node = page.selectedNode
  if (!node || node.type !== 'exclusive-gateway' || !page.plan) return []
  const labelById = new Map(
    (page.plan.nodes ?? []).map(n => [
      n.id,
      n.displayName?.trim() || formatPlanNodeType(n.type) || n.id,
    ]),
  )
  return (page.plan.edges ?? [])
    .filter(e => e.from === node.id)
    .map(e => ({
      ...e,
      toLabel: labelById.get(e.to) || e.to,
      conditionGroup: normalizeEdgeConditionGroup(e.condition),
    }))
})

const gatewayUpstreamNodes = computed(() => {
  if (!page.plan || !page.selectedNode) return []
  return upstreamNodesOf(page.plan, page.selectedNode.id)
})

function updateEdgeCondition(to: string, group: WorkflowPlanEdgeConditionGroup) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const from = page.selectedNode.id
  const edges = (page.plan.edges ?? []).map(e => {
    if (e.to !== to || e.from !== from) return e
    if (group.items.length === 0) {
      const { condition: _c, ...rest } = e
      return rest
    }
    return { ...e, condition: group }
  })
  page.plan = { ...page.plan, edges }
}

function updateEdgeDefault(to: string, isDefault: boolean) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const plan = page.plan
  const from = page.selectedNode.id
  const edges = (plan.edges ?? []).map(e => {
    if (e.from !== from) return e
    if (e.to === to) {
      if (isDefault) {
        const { condition: _c, ...rest } = e
        return { ...rest, default: true }
      }
      const { default: _d, ...rest } = e
      const left = exclusiveGatewayConditionLeft(plan, from)
      return { ...rest, condition: rest.condition ?? { logic: 'and', items: [{ left, op: 'contains', right: '' }] } }
    }
    // 切换为默认分支时，清除同源其他边的 default 标记，恢复条件结构
    if (isDefault && e.default) {
      const { default: _d, ...rest } = e
      const left = exclusiveGatewayConditionLeft(plan, from)
      return { ...rest, condition: rest.condition ?? { logic: 'and', items: [{ left, op: 'contains', right: '' }] } }
    }
    return e
  })
  page.plan = { ...plan, edges }
}
</script>

<template>
  <WorkflowNodeConfigSection title="条件分支" :help="workflowNodeFieldHelp('exclusiveGatewayTopology')">
    <p v-if="gatewayTopology" class="join-topology-lines">
      <span :class="{ 'join-ok': gatewayTopology.okOut, 'join-warn': !gatewayTopology.okOut }">
        出边 {{ gatewayTopology.out }} 条{{ gatewayTopology.okOut ? '' : '（须 ≥ 2）' }}
      </span>
    </p>
    <p class="join-topology-hint">按条件选择其中一条路继续；须恰好一条默认分支。</p>
    <div
      v-for="edge in exclusiveOutEdges"
      :key="`${edge.from}->${edge.to}`"
      class="exclusive-edge-card"
    >
      <div class="exclusive-edge-head">
        <div class="exclusive-edge-target" :title="edge.to">
          <span class="exclusive-edge-to-label">-> {{ edge.toLabel }}</span>
          <span class="exclusive-edge-to-id">{{ edge.to }}</span>
        </div>
        <label class="exclusive-default">
          <input
            type="checkbox"
            :checked="!!edge.default"
            :disabled="readOnly"
            @change="updateEdgeDefault(edge.to, ($event.target as HTMLInputElement).checked)"
          />
          默认
        </label>
      </div>
      <template v-if="!edge.default">
        <ConditionGroupEditor
          :model-value="edge.conditionGroup"
          :upstream-nodes="gatewayUpstreamNodes"
          :disabled="readOnly"
          @update:modelValue="g => updateEdgeCondition(edge.to, g)"
        />
      </template>
    </div>
  </WorkflowNodeConfigSection>
</template>

<style scoped>
.join-topology-lines {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--sun-text-secondary);
}
.join-topology-lines .join-ok {
  color: var(--sun-text-secondary);
}
.join-topology-lines .join-warn {
  color: var(--sun-amber);
}
.join-topology-hint {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--sun-text-muted);
  line-height: 1.4;
}
.exclusive-edge-card {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--sun-black);
}
.exclusive-edge-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.exclusive-edge-target {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.exclusive-edge-to-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.35;
  word-break: break-word;
}
.exclusive-edge-to-id {
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono);
  word-break: break-all;
}
.exclusive-default {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--sun-text-secondary);
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
}
.exclusive-default input {
  margin: 0;
}
</style>
