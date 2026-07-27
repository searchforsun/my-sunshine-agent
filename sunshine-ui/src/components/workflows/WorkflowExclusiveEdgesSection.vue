<script setup lang="ts">
import { computed, inject } from 'vue'
import { NFormItem, NInput, NSelect } from 'naive-ui'
import WorkflowNodeConfigSection from './WorkflowNodeConfigSection.vue'
import { formatPlanNodeType } from '../../api/executionPlans'
import { workflowNodeFieldHelp } from './workflowFieldHelp'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import { countNodeDegree } from '../../utils/workflowPlanValidation'
import { exclusiveGatewayConditionLeft } from '../../utils/workflowPlan'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi
const readOnly = computed(() => !page.canEditPlan)

const CONDITION_OP_OPTIONS = [
  { label: '为空 empty', value: 'empty' },
  { label: '非空 not_empty', value: 'not_empty' },
  { label: '包含 contains', value: 'contains' },
  { label: '等于 eq', value: 'eq' },
  { label: '大于 gt', value: 'gt' },
  { label: '小于 lt', value: 'lt' },
  { label: '大于等于 gte', value: 'gte' },
  { label: '小于等于 lte', value: 'lte' },
  { label: '属于 in', value: 'in' },
  { label: '不属于 not_in', value: 'not_in' },
]

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
  const autoLeft = exclusiveGatewayConditionLeft(page.plan, node.id)
  return (page.plan.edges ?? [])
    .filter(e => e.from === node.id)
    .map(e => ({
      ...e,
      toLabel: labelById.get(e.to) || e.to,
      condition: e.default
        ? e.condition
        : {
            left: autoLeft,
            op: e.condition?.op ?? 'contains',
            right: e.condition?.right ?? '',
          },
    }))
})

function updateExclusiveEdge(
  to: string,
  patch: { default?: boolean; condition?: { left: string; op: string; right?: string } | null },
) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const from = page.selectedNode.id
  const autoLeft = exclusiveGatewayConditionLeft(page.plan, from)
  const edges = (page.plan.edges ?? []).map(e => {
    if (e.from !== from) return e
    if (patch.default === true && e.to !== to && e.default) {
      const { default: _d, ...rest } = e
      return {
        ...rest,
        condition: rest.condition
          ? { ...rest.condition, left: autoLeft }
          : { left: autoLeft, op: 'contains', right: '' },
      }
    }
    if (e.to !== to) return e
    const next = { ...e }
    if (patch.default === true) {
      next.default = true
      delete next.condition
      return next
    }
    if (patch.default === false) {
      delete next.default
      next.condition = {
        left: autoLeft,
        op: next.condition?.op ?? 'contains',
        right: next.condition?.right ?? '',
      }
      return next
    }
    if (patch.condition === null) {
      delete next.condition
    } else if (patch.condition) {
      next.condition = { ...patch.condition, left: autoLeft }
      delete next.default
    }
    return next
  })
  page.plan = { ...page.plan, edges }
}

function updateExclusiveEdgeField(to: string, field: 'op' | 'right', value: string) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const from = page.selectedNode.id
  const edge = (page.plan.edges ?? []).find(e => e.from === from && e.to === to)
  const condition = {
    left: exclusiveGatewayConditionLeft(page.plan, from),
    op: edge?.condition?.op ?? 'contains',
    right: edge?.condition?.right ?? '',
    [field]: value,
  }
  updateExclusiveEdge(to, { condition })
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
          <span class="exclusive-edge-to-label">→ {{ edge.toLabel }}</span>
          <span class="exclusive-edge-to-id">{{ edge.to }}</span>
        </div>
        <label class="exclusive-default">
          <input
            type="checkbox"
            :checked="!!edge.default"
            :disabled="readOnly"
            @change="updateExclusiveEdge(edge.to, { default: ($event.target as HTMLInputElement).checked })"
          >
          默认
        </label>
      </div>
      <template v-if="!edge.default">
        <NFormItem label="左值（随上游自动填入）" :show-feedback="false">
          <NInput
            :value="edge.condition?.left ?? ''"
            disabled
            placeholder="{{start.userQuery}}"
          />
        </NFormItem>
        <NFormItem label="算子" :show-feedback="false">
          <NSelect
            :value="edge.condition?.op || 'contains'"
            :options="CONDITION_OP_OPTIONS"
            :disabled="readOnly"
            @update:value="v => updateExclusiveEdgeField(edge.to, 'op', String(v))"
          />
        </NFormItem>
        <NFormItem
          v-if="edge.condition?.op !== 'empty' && edge.condition?.op !== 'not_empty'"
          label="右值"
          :show-feedback="false"
        >
          <NInput
            :value="edge.condition?.right ?? ''"
            :disabled="readOnly"
            placeholder="比较值"
            @update:value="v => updateExclusiveEdgeField(edge.to, 'right', v)"
          />
        </NFormItem>
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
