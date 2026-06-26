<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { resolvePlanIdFromStep } from '../../api/processingSteps'
import { findHitlStep } from '../../api/recoverySteps'
import { isHitlToolStep, isToolStepId, resolveHitlUiKey, type HitlConfirmationPayload } from '../../api/hitlSteps'
import OperationCard from './OperationCard.vue'
import HitlStepActions from './HitlStepActions.vue'
import PlanWorkflowPanel from '../plan/PlanWorkflowPanel.vue'

const props = defineProps<{
  steps: ProcessingStep[]
  live?: boolean
  executionPlanId?: string
  userQuery?: string
  timelineRevision?: number
  embedHitl?: boolean
  pendingHitlConfirmation?: HitlConfirmationPayload
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
  return !!resolvePlanIdFromStep(plan)
    || !!(plan.metadata?.planApproval?.planGraph?.nodes?.length)
    || !!props.executionPlanId
})

const displaySteps = computed(() => {
  void props.timelineRevision
  if (!showPlanDag.value) return props.steps
  return props.steps.filter(s => {
    if (s.phase === 'node') return false
    if (isToolStepId(s.id)) return false
    if (s.id === 'think' || s.id.startsWith('think-')) return false
    return true
  })
})

const hitlRevision = computed(() =>
  resolveHitlUiKey(props.steps, props.pendingHitlConfirmation),
)

/** Plan DAG 滤掉 tool 步时，主 timeline 底部展示写工具 HITL */
const filteredToolHitl = computed((): ProcessingStep | undefined => {
  void props.timelineRevision
  void hitlRevision.value
  if (props.embedHitl === false || !showPlanDag.value) return undefined
  const displayed = new Set(displaySteps.value.map(s => s.id))
  for (let i = props.steps.length - 1; i >= 0; i--) {
    const step = props.steps[i]
    if (!isToolStepId(step.id) || displayed.has(step.id)) continue
    const found = findHitlStep(step, props.pendingHitlConfirmation)
    if (!found) continue
    const st = found.metadata?.hitlStatus
    if (st === 'approved' || st === 'denied') continue
    return found
  }
  return undefined
})

const filteredHitlKey = computed(() =>
  filteredToolHitl.value?.metadata?.hitlToken
  ?? filteredToolHitl.value?.id
  ?? '',
)

/** ReAct 主 timeline：tool 步 id → 待确认步骤（供步骤行正下方渲染） */
const inlineHitlByStepId = computed(() => {
  void props.timelineRevision
  void hitlRevision.value
  const map = new Map<string, ProcessingStep>()
  if (props.embedHitl === false || showPlanDag.value) return map
  for (const step of displaySteps.value) {
    if (!isHitlToolStep(step)) continue
    const found = findHitlStep(step, props.pendingHitlConfirmation)
    if (!found) continue
    const st = found.metadata?.hitlStatus
    if (st === 'approved' || st === 'denied') continue
    map.set(step.id, found)
  }
  return map
})
</script>

<template>
  <div class="operation-lines">
    <template v-for="step in displaySteps" :key="`${step.id}-${hitlRevision}-${step.summary?.active ?? ''}`">
      <PlanWorkflowPanel
        v-if="step.phase === 'plan' && showPlanDag"
        :plan-step="step"
        :all-steps="steps"
        :live="live"
        :execution-plan-id="executionPlanId"
        :user-query="userQuery"
      />
      <template v-else>
        <OperationCard
          :step="step"
          :expanded="isCardExpanded(step)"
          :live="live && lifecycleOf(step) === 'running'"
          :execution-plan-id="executionPlanId"
          :embed-hitl="false"
          @toggle="toggleCard(step)"
        />
        <HitlStepActions
          v-if="inlineHitlByStepId.get(step.id)"
          :key="inlineHitlByStepId.get(step.id)!.metadata?.hitlToken ?? step.id"
          :step="inlineHitlByStepId.get(step.id)!"
          :pending-confirmation="pendingHitlConfirmation"
          @decided="(token, approved) => emit('hitlDecided', token, approved)"
        />
      </template>
    </template>
    <section v-if="filteredToolHitl" class="operation-hitl-section">
      <p class="operation-hitl-title">写操作确认</p>
      <HitlStepActions
        :key="filteredHitlKey"
        :step="filteredToolHitl"
        :pending-confirmation="pendingHitlConfirmation"
        @decided="(token, approved) => emit('hitlDecided', token, approved)"
      />
    </section>
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

.operation-hitl-section {
  margin: 6px 0 2px calc(var(--op-gutter, 12px) + 4px);
  padding-left: 4px;
}

.operation-hitl-title {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  color: var(--sun-text-secondary);
}
</style>
