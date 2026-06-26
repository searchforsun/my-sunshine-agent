<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { resolvePlanIdFromStep } from '../../api/processingSteps'
import { isToolStepId } from '../../api/hitlSteps'
import OperationCard from './OperationCard.vue'
import PlanWorkflowPanel from '../plan/PlanWorkflowPanel.vue'

const props = defineProps<{
  steps: ProcessingStep[]
  live?: boolean
  executionPlanId?: string
  userQuery?: string
  /** 为 false 时 HITL 由外层（如 PlanNodeDrawer）统一展示 */
  embedHitl?: boolean
}>()

const emit = defineEmits<{
  hitlDecided: [token: string, approved: boolean]
}>()

const cardExpanded = reactive(new Map<string, boolean>())
const cardUserToggled = reactive(new Set<string>())

function lifecycleOf(step: ProcessingStep) {
  return step.lifecycle ?? step.status ?? 'pending'
}

function isCardExpanded(step: ProcessingStep): boolean {
  if (cardUserToggled.has(step.id)) {
    return cardExpanded.get(step.id) ?? false
  }
  return false
}

function toggleCard(step: ProcessingStep): void {
  cardUserToggled.add(step.id)
  cardExpanded.set(step.id, !isCardExpanded(step))
}

const planStep = computed(() => props.steps.find(s => s.phase === 'plan'))

const showPlanDag = computed(() => {
  const plan = planStep.value
  if (!plan) return false
  // 仅校验通过的路径下发 planId=；降级步不含 planId，不展示 DAG
  return !!resolvePlanIdFromStep(plan)
})

const displaySteps = computed(() => {
  if (!showPlanDag.value) return props.steps
  // Plan DAG 模式：node 在面板内展示；子 Agent 泄漏的顶层 tool/think 不入主时间线
  return props.steps.filter(s => {
    if (s.phase === 'node') return false
    if (isToolStepId(s.id)) return false
    if (s.id === 'think' || s.id.startsWith('think-')) return false
    return true
  })
})
</script>

<template>
  <div class="operation-lines">
    <template v-for="step in displaySteps" :key="step.id">
      <PlanWorkflowPanel
        v-if="step.phase === 'plan' && showPlanDag"
        :plan-step="step"
        :all-steps="steps"
        :live="live"
        :execution-plan-id="executionPlanId"
        :user-query="userQuery"
      />
      <OperationCard
        v-else
        :step="step"
        :expanded="isCardExpanded(step)"
        :live="live && lifecycleOf(step) === 'running'"
        :execution-plan-id="executionPlanId"
        :embed-hitl="embedHitl !== false"
        @toggle="toggleCard(step)"
        @hitl-decided="(token, approved) => emit('hitlDecided', token, approved)"
      />
    </template>
  </div>
</template>

<style scoped>
.operation-lines {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 0 8px;
  margin-left: -2px;
}
</style>
