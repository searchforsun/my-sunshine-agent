<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { hasRealTaskBoardItems, resolvePlanIdFromStep } from '../../api/processingSteps'
import {
  isToolStepId,
  resolveHitlUiKey,
  isHitlCarrierStep,
  hasHitlPanel,
  isHitlAwaiting,
  isHitlSummaryAwaiting,
  resolvePendingHitlForStep,
  resolveStepForHitlDisplay,
  normalizePendingHitlList,
  resolveHitlToken,
  type HitlConfirmationPayload,
} from '../../api/hitlSteps'
import type { ContentBlock } from '../../api/contentInterleave'
import {
  contentRowsAfterStep,
  isHiddenReactTimelineStep,
  orphanContentRows,
  resolveLastContentBlockIndex,
} from '../../api/contentInterleave'
import OperationCard from './OperationCard.vue'
import TaskBoardPanel from './TaskBoardPanel.vue'
import PeerCollabPanel from './PeerCollabPanel.vue'
import HitlStepActions from './HitlStepActions.vue'
import PlanWorkflowPanel from '../plan/PlanWorkflowPanel.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import { ensurePlanTimelineSteps, isPlanDagNodeStep } from '../../api/planHydrate'

const props = withDefaults(defineProps<{
  steps: ProcessingStep[]
  live?: boolean
  executionPlanId?: string
  userQuery?: string
  timelineRevision?: number
  /** ReAct 正文分段，穿插在步骤之间 */
  contentBlocks?: ContentBlock[]
  /** ReAct 穿插正文是否仍在输出（保留 prop 供 timeline 刷新） */
  streamLive?: boolean
  /** 仅控制 OperationCard 内嵌 HITL；主 timeline 行下确认框见 inlineHitl */
  embedHitl?: boolean
  /** 步骤行下方 HitlStepActions（ReAct 主 timeline / 抽屉 subSteps）；仅 Plan 抽屉纯 tool 单行等特殊场景传 false */
  inlineHitl?: boolean
  /** assistant 消息 id — peer-collab 展开 transcript 审计 */
  messageId?: string
  pendingHitlConfirmation?: HitlConfirmationPayload | HitlConfirmationPayload[]
  pendingHitlConfirmations?: HitlConfirmationPayload[]
}>(), {
  embedHitl: true,
  inlineHitl: true,
  contentBlocks: undefined,
  streamLive: false,
})

const emit = defineEmits<{
  hitlDecided: [token: string, approved: boolean]
}>()

const cardExpanded = reactive(new Map<string, boolean>())
const cardUserToggled = reactive(new Set<string>())

function lifecycleOf(step: ProcessingStep) {
  return step.lifecycle ?? 'pending'
}

function isCardExpanded(step: ProcessingStep): boolean {
  if (cardUserToggled.has(step.id)) {
    return cardExpanded.get(step.id) ?? false
  }
  // loop 框内 agent：运行中默认展开，便于看流式 think/正文；结束后默认收起
  if (hasNestedLoopBodyTimeline(step)) {
    return lifecycleOf(step) === 'running'
  }
  return false
}

function hasNestedLoopBodyTimeline(step: ProcessingStep): boolean {
  return !!step.id?.startsWith('i')
    && !!(step.subSteps?.length || step.contentBlocks?.length)
}

function toggleCard(step: ProcessingStep): void {
  cardUserToggled.add(step.id)
  cardExpanded.set(step.id, !isCardExpanded(step))
}

const effectiveSteps = computed(() => ensurePlanTimelineSteps({
  steps: props.steps,
  executionPlanId: props.executionPlanId,
}))

const planStep = computed(() => effectiveSteps.value.find(s => s.phase === 'plan'))

const showPlanDag = computed(() => {
  const plan = planStep.value
  if (!plan) return false
  return !!resolvePlanIdFromStep(plan)
    || !!(plan.metadata?.planApproval?.planGraph?.nodes?.length)
    || !!props.executionPlanId
})

const displaySteps = computed(() => {
  void props.timelineRevision
  if (showPlanDag.value) {
    return effectiveSteps.value.filter(s => {
      if (s.phase === 'node' || isPlanDagNodeStep(s)) return false
      if (s.phase === 'tasks') return false
      if (s.phase === 'peer-collab') return false
      if (s.phase === 'expert-convene') return false
      if (isToolStepId(s.id)) return false
      if (s.id === 'think' || s.id.startsWith('think-')) return false
      return true
    })
  }
  // ReAct：正文已 inline 穿插，不再展示「生成回答」步骤行；无 items 的 tasks 占位步不展示
  return props.steps.filter(s => {
    if (isHiddenReactTimelineStep(s)) return false
    if (s.phase === 'tasks' && !hasRealTaskBoardItems(s)) return false
    return true
  })
})

const pendingList = computed(() =>
  normalizePendingHitlList(props.pendingHitlConfirmations ?? props.pendingHitlConfirmation),
)

const hitlRevision = computed(() =>
  resolveHitlUiKey(props.steps, pendingList.value),
)

function pendingForStep(step: ProcessingStep): HitlConfirmationPayload | undefined {
  return resolvePendingHitlForStep(step, pendingList.value, props.steps)
}

function hitlStepKey(step: ProcessingStep): string {
  const token = resolveHitlToken(step) ?? pendingForStep(step)?.confirmationToken
  return `${step.id}-${token ?? step.metadata?.hitlStatus ?? 'open'}`
}

function shouldShowInlineHitl(step: ProcessingStep): boolean {
  if (props.inlineHitl === false || showPlanDag.value) return false
  if (!isHitlCarrierStep(step)) return false
  const pending = resolvePendingHitlForStep(step, pendingList.value, props.steps)
  return hasHitlPanel(step)
    || isHitlAwaiting(step)
    || isHitlSummaryAwaiting(step)
    || !!pending
}

function inlineHitlStep(step: ProcessingStep): ProcessingStep {
  return resolveStepForHitlDisplay(step, pendingList.value, props.steps)
}

const contentRowOpts = computed(() => ({
  live: props.streamLive || props.live,
  lastBlockIndex: resolveLastContentBlockIndex(props.contentBlocks),
}))

const visibleStepIds = computed(() => new Set(displaySteps.value.map(s => s.id)))

function rowsAfterStep(stepId: string) {
  void props.timelineRevision
  return contentRowsAfterStep(
    stepId,
    props.steps,
    visibleStepIds.value,
    props.contentBlocks,
    contentRowOpts.value,
  )
}

const orphanContent = computed(() => {
  void props.timelineRevision
  return orphanContentRows(
    props.steps,
    visibleStepIds.value,
    props.contentBlocks,
    contentRowOpts.value,
  )
})
</script>

<template>
  <div class="operation-lines">
    <template v-for="step in displaySteps" :key="`${step.id}-${hitlRevision}-${step.summary?.active ?? ''}`">
      <PlanWorkflowPanel
        v-if="step.phase === 'plan' && showPlanDag"
        :plan-step="step"
        :all-steps="effectiveSteps"
        :live="live"
        :execution-plan-id="executionPlanId"
        :user-query="userQuery"
        :pending-hitl-confirmation="pendingList"
      />
      <TaskBoardPanel
        v-else-if="step.phase === 'tasks'"
        :step="step"
        :live="live && lifecycleOf(step) === 'running'"
      />
      <PeerCollabPanel
        v-else-if="step.phase === 'peer-collab'"
        :step="step"
        :message-id="messageId"
        :live="live && lifecycleOf(step) === 'running'"
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
        <div v-if="shouldShowInlineHitl(step)" class="op-line-hitl">
          <span class="op-gutter" aria-hidden="true" />
          <HitlStepActions
            :key="hitlStepKey(inlineHitlStep(step))"
            :step="inlineHitlStep(step)"
            :pending-confirmation="pendingForStep(step)"
            @decided="(token, approved) => emit('hitlDecided', token, approved)"
          />
        </div>
        <!-- loop 框内 agent：嵌套 think/正文随卡片展开收起 -->
        <div
          v-if="isCardExpanded(step) && hasNestedLoopBodyTimeline(step)"
          class="op-nested-stack"
        >
          <OperationStack
            :steps="step.subSteps ?? []"
            :content-blocks="step.contentBlocks"
            :stream-live="streamLive && lifecycleOf(step) === 'running'"
            :live="live && lifecycleOf(step) === 'running'"
            :embed-hitl="false"
            :pending-hitl-confirmation="pendingList"
            @hitl-decided="(token, approved) => emit('hitlDecided', token, approved)"
          />
        </div>
      </template>
      <!-- Plan DAG 下 node-answer 正文锚定到 plan，须在 PlanWorkflowPanel 之后渲染 -->
      <template v-for="crow in rowsAfterStep(step.id)" :key="crow.key">
        <div class="op-inline-content">
          <span class="op-gutter" aria-hidden="true" />
          <div class="op-inline-body" :class="{ 'is-streaming-md': crow.streaming }">
            <StaticMarkdown :source="crow.text" :defer-mermaid="crow.streaming" />
          </div>
        </div>
      </template>
    </template>
    <template v-for="row in orphanContent" :key="row.key">
      <div class="op-inline-content">
        <span class="op-gutter" aria-hidden="true" />
        <div class="op-inline-body" :class="{ 'is-streaming-md': row.streaming }">
          <StaticMarkdown :source="row.text" :defer-mermaid="row.streaming" />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.operation-lines {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 0 12px;
  margin-left: -2px;
}

.op-line-hitl {
  --op-gutter: 12px;
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
  margin-top: 6px;
}

.op-line-hitl + .op-line-hitl {
  margin-top: 10px;
}

.op-line-hitl .op-gutter {
  width: var(--op-gutter);
  flex-shrink: 0;
}

.op-inline-content {
  --op-gutter: 12px;
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
  margin: 4px 0 8px;
}

.op-nested-stack {
  margin: 2px 0 8px 16px;
  padding-left: 8px;
  border-left: 1px solid var(--sun-border);
}

.op-inline-content .op-gutter {
  width: var(--op-gutter);
  flex-shrink: 0;
}

.op-inline-body {
  min-width: 0;
}

.op-inline-body :deep(.msg-md) {
  padding: 0;
  margin: 0;
}

.op-inline-body.is-streaming-md :deep(.msg-md) {
  min-height: 1.5em;
}

.op-line-hitl :deep(.collapsible-confirm) {
  --confirm-inset-left: 0;
  margin-left: 0;
}
</style>
