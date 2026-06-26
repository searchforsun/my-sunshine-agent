<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { resolvePlanIdFromStep } from '../../api/processingSteps'
import { findHitlStep } from '../../api/recoverySteps'
import { isToolStepId, resolveHitlUiKey, type HitlConfirmationPayload } from '../../api/hitlSteps'
import OperationCard from './OperationCard.vue'
import HitlStepActions from './HitlStepActions.vue'
import PlanWorkflowPanel from '../plan/PlanWorkflowPanel.vue'

const props = withDefaults(defineProps<{
  steps: ProcessingStep[]
  live?: boolean
  executionPlanId?: string
  userQuery?: string
  timelineRevision?: number
  /** 仅控制 OperationCard 内嵌 HITL；主 timeline 行下确认框见 inlineHitl */
  embedHitl?: boolean
  /** 步骤行下方 HitlStepActions（ReAct 主 timeline / 抽屉 subSteps）；仅 Plan 抽屉纯 tool 单行等特殊场景传 false */
  inlineHitl?: boolean
  pendingHitlConfirmation?: HitlConfirmationPayload
}>(), {
  embedHitl: true,
  inlineHitl: true,
})

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

/** Plan DAG 滤掉 tool 步时，主 timeline 底部展示写工具 HITL（含已确认/已取消） */
const filteredToolHitl = computed((): ProcessingStep | undefined => {
  void props.timelineRevision
  void hitlRevision.value
  if (props.inlineHitl === false || !showPlanDag.value) return undefined
  const displayed = new Set(displaySteps.value.map(s => s.id))
  for (let i = props.steps.length - 1; i >= 0; i--) {
    const step = props.steps[i]
    if (!isToolStepId(step.id) || displayed.has(step.id)) continue
    const found = findHitlStep(step, props.pendingHitlConfirmation)
    if (found) return found
  }
  return undefined
})

function hitlStepKey(step: ProcessingStep): string {
  return step.metadata?.hitlToken
    ?? step.metadata?.hitlStatus
    ?? step.id
}

/** ReAct / 抽屉 subSteps：步骤 id → HITL 步骤（待确认 + 已决态） */
const inlineHitlByStepId = computed(() => {
  void props.timelineRevision
  void hitlRevision.value
  const map = new Map<string, ProcessingStep>()
  if (props.inlineHitl === false || showPlanDag.value) return map
  for (const step of displaySteps.value) {
    const found = findHitlStep(step, props.pendingHitlConfirmation)
    if (found) map.set(step.id, found)
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
        <div v-if="inlineHitlByStepId.get(step.id)" class="op-line-hitl">
          <span class="op-gutter" aria-hidden="true" />
          <HitlStepActions
            :key="hitlStepKey(inlineHitlByStepId.get(step.id)!)"
            :step="inlineHitlByStepId.get(step.id)!"
            :pending-confirmation="pendingHitlConfirmation"
            @decided="(token, approved) => emit('hitlDecided', token, approved)"
          />
        </div>
      </template>
    </template>
    <div v-if="filteredToolHitl" class="op-line-hitl operation-hitl-fallback">
      <span class="op-gutter" aria-hidden="true" />
      <div class="operation-hitl-body">
        <p class="operation-hitl-title">写操作确认</p>
        <HitlStepActions
          :key="filteredToolHitl ? hitlStepKey(filteredToolHitl) : ''"
          :step="filteredToolHitl"
          :pending-confirmation="pendingHitlConfirmation"
          @decided="(token, approved) => emit('hitlDecided', token, approved)"
        />
      </div>
    </div>
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

.op-line-hitl {
  --op-gutter: 12px;
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
}

.op-line-hitl .op-gutter {
  width: var(--op-gutter);
  flex-shrink: 0;
}

.op-line-hitl :deep(.collapsible-confirm) {
  --confirm-inset-left: 0;
  margin-left: 0;
}

.operation-hitl-fallback {
  margin-top: 6px;
}

.operation-hitl-body {
  min-width: 0;
}

.operation-hitl-title {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  color: var(--sun-text-secondary);
}
</style>
