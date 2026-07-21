<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import type { ProcessingStep, TimelineMessageStatus } from '../../api/processingSteps'
import {
  formatElapsedClock,
  formatTimelineSummaryText,
  hasRealTaskBoardItems,
  isSubagentStep,
  resolvePlanIdFromStep,
  resolveTimelineElapsedMs,
  resolveTimelineSummaryPrefix,
} from '../../api/processingSteps'
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
  isContentFullyInterleaved,
  isHiddenReactTimelineStep,
  orphanContentRows,
  resolveCollapsedAnswerText,
  resolveLastContentBlockIndex,
} from '../../api/contentInterleave'
import OperationCard from './OperationCard.vue'
import TaskBoardPanel from './TaskBoardPanel.vue'
import PeerCollabPanel from './PeerCollabPanel.vue'
import SubagentCard from './SubagentCard.vue'
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
  /** Chat 顶层传入时启用总览行；嵌套 Stack / 抽屉勿传 */
  messageStatus?: TimelineMessageStatus
  /** assistant msg.content，折叠终稿优先 */
  messageContent?: string
  /** 墙钟 start（进入 streaming / API createdAt） */
  timelineStartedAt?: number
  /** 墙钟 end（正文结束 / API updatedAt） */
  timelineEndedAt?: number
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

const summaryEnabled = computed(() => props.messageStatus !== undefined)

const timelineUserToggled = ref(false)
const timelineExpandedOverride = ref(false)

const timelineBodyExpanded = computed(() => {
  if (!summaryEnabled.value) return true
  if (timelineUserToggled.value) return timelineExpandedOverride.value
  // 进行中 / 终态均默认折叠；用户点开后以 userToggled 为准
  return false
})

function toggleTimelineBody(): void {
  if (!summaryEnabled.value) return
  const next = !timelineBodyExpanded.value
  timelineUserToggled.value = true
  timelineExpandedOverride.value = next
}

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

const isTimelineProcessing = computed(() =>
  !!(props.live || props.messageStatus === 'streaming'),
)

const collapsedAnswerText = computed(() => {
  if (!summaryEnabled.value || timelineBodyExpanded.value) return ''
  // 正在处理 / 终态折叠：均取最后一段 contentBlock（进行中也要露流式正文）
  return resolveCollapsedAnswerText({
    role: 'assistant',
    content: props.messageContent ?? '',
    steps: effectiveSteps.value,
    contentBlocks: props.contentBlocks,
  })
})

/** 终态折叠：仅 interleaved 时由 Stack 渲染终稿，避免与底栏 msg-md 双显 */
const showCollapsedAnswer = computed(() => {
  if (!collapsedAnswerText.value || isTimelineProcessing.value) return false
  return isContentFullyInterleaved({
    role: 'assistant',
    content: props.messageContent ?? '',
    steps: effectiveSteps.value,
    contentBlocks: props.contentBlocks,
  })
})

const fallbackStartMs = ref<number | undefined>(undefined)
const nowMs = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | undefined

const isMessageTerminal = computed(() => {
  const s = props.messageStatus
  return s === 'completed' || s === 'interrupted' || s === 'failed'
})

/** 含正文：streaming（或未终态且 live）期间用 now 单调上涨；终态停表 */
const summaryClockLive = computed(() => {
  if (!summaryEnabled.value || isMessageTerminal.value) return false
  return props.messageStatus === 'streaming' || !!props.live
})

watch(
  summaryClockLive,
  (live) => {
    if (!live) return
    if (fallbackStartMs.value == null) fallbackStartMs.value = Date.now()
  },
  { immediate: true },
)

watch(
  summaryClockLive,
  (needTick) => {
    if (tickTimer) {
      clearInterval(tickTimer)
      tickTimer = undefined
    }
    if (!needTick) return
    nowMs.value = Date.now()
    tickTimer = setInterval(() => { nowMs.value = Date.now() }, 200)
  },
  { immediate: true },
)

onUnmounted(() => {
  if (tickTimer) clearInterval(tickTimer)
})

const summaryText = computed(() => {
  if (!summaryEnabled.value) return ''
  const elapsed = resolveTimelineElapsedMs({
    steps: effectiveSteps.value,
    live: summaryClockLive.value,
    nowMs: nowMs.value,
    fallbackStartMs: props.timelineStartedAt ?? fallbackStartMs.value,
    fallbackEndMs: props.timelineEndedAt,
  })
  const clock = elapsed != null ? formatElapsedClock(elapsed) : ''
  const prefix = resolveTimelineSummaryPrefix({
    live: !!props.live,
    messageStatus: props.messageStatus,
  })
  return formatTimelineSummaryText(prefix, clock)
})

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
      if (isSubagentStep(s)) return false
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

/** 正在处理且整段折叠：只露 displaySteps 最后一条（折叠概要，无穿插正文） */
const collapsedPreviewStep = computed(() => {
  if (!summaryEnabled.value || timelineBodyExpanded.value || !isTimelineProcessing.value) {
    return undefined
  }
  const steps = displaySteps.value
  return steps.length ? steps[steps.length - 1] : undefined
})

/** 正在处理折叠：在步骤概要下继续展示最后一段正文（contentBlocks） */
const showProcessingCollapsedAnswer = computed(() =>
  !!collapsedPreviewStep.value && !!collapsedAnswerText.value,
)

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
    <div
      v-if="summaryEnabled"
      class="op-line timeline-summary"
      :class="{
        'is-expanded': timelineBodyExpanded,
        'is-clickable': true,
        'is-running': live || messageStatus === 'streaming',
      }"
    >
      <button type="button" class="op-line-row" @click="toggleTimelineBody">
        <span class="op-gutter">
          <svg
            class="op-chevron"
            width="9"
            height="9"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            stroke-linecap="round"
          >
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </span>
        <span class="op-main">
          <span
            class="op-label"
            :class="{ 'op-shimmer': live || messageStatus === 'streaming' }"
          >{{ summaryText }}</span>
        </span>
      </button>
    </div>

    <template v-if="timelineBodyExpanded">
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
        <SubagentCard
          v-else-if="isSubagentStep(step)"
          :step="step"
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
    </template>
    <!-- 正在处理折叠：最后一步概要（无 chevron）+ 最后一段正文 -->
    <template v-else-if="collapsedPreviewStep">
      <PlanWorkflowPanel
        v-if="collapsedPreviewStep.phase === 'plan' && showPlanDag"
        :plan-step="collapsedPreviewStep"
        :all-steps="effectiveSteps"
        :live="live"
        :execution-plan-id="executionPlanId"
        :user-query="userQuery"
        :pending-hitl-confirmation="pendingList"
      />
      <TaskBoardPanel
        v-else-if="collapsedPreviewStep.phase === 'tasks'"
        :step="collapsedPreviewStep"
        :live="live && lifecycleOf(collapsedPreviewStep) === 'running'"
      />
      <PeerCollabPanel
        v-else-if="collapsedPreviewStep.phase === 'peer-collab'"
        :step="collapsedPreviewStep"
        :message-id="messageId"
        :live="live && lifecycleOf(collapsedPreviewStep) === 'running'"
      />
      <SubagentCard
        v-else-if="isSubagentStep(collapsedPreviewStep)"
        :step="collapsedPreviewStep"
        :live="live && lifecycleOf(collapsedPreviewStep) === 'running'"
      />
      <OperationCard
        v-else
        :step="collapsedPreviewStep"
        :expanded="false"
        :hide-chevron="true"
        :live="live && lifecycleOf(collapsedPreviewStep) === 'running'"
        :execution-plan-id="executionPlanId"
        :embed-hitl="false"
      />
      <div
        v-if="showProcessingCollapsedAnswer"
        class="op-inline-content timeline-collapsed-answer"
      >
        <span class="op-gutter" aria-hidden="true" />
        <div class="op-inline-body" :class="{ 'is-streaming-md': streamLive || live }">
          <StaticMarkdown
            :source="collapsedAnswerText"
            :defer-mermaid="!!(streamLive || live)"
          />
        </div>
      </div>
    </template>
    <div
      v-else-if="showCollapsedAnswer"
      class="op-inline-content timeline-collapsed-answer"
    >
      <span class="op-gutter" aria-hidden="true" />
      <div class="op-inline-body">
        <StaticMarkdown :source="collapsedAnswerText" />
      </div>
    </div>
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

.timeline-summary {
  --op-gutter: 12px;
  font-size: var(--sun-font-md);
  line-height: 1.5;
  color: var(--sun-text-muted);
  margin-bottom: 2px;
}

.timeline-summary .op-line-row {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
  width: 100%;
  padding: 1px 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.timeline-summary .op-gutter {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  width: var(--op-gutter);
  padding-top: 4px;
  flex-shrink: 0;
}

.timeline-summary .op-chevron {
  flex-shrink: 0;
  color: var(--sun-text-muted);
  opacity: 0.5;
  display: inline-block;
  transition: transform 0.15s ease;
  transform: rotate(0deg);
}

.timeline-summary.is-expanded .op-chevron {
  transform: rotate(90deg);
}

.timeline-summary .op-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.timeline-summary .op-label {
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.timeline-summary.is-clickable:hover .op-label {
  color: var(--sun-text);
}

.op-shimmer {
  --op-shimmer-base: var(--sun-text-muted);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text-muted) 32%, white);
  display: inline-block;
  max-width: 100%;
  background-image: linear-gradient(
    90deg,
    var(--op-shimmer-base) 0%,
    var(--op-shimmer-base) 36%,
    var(--op-shimmer-peak) 50%,
    var(--op-shimmer-base) 64%,
    var(--op-shimmer-base) 100%
  );
  background-size: 220% 100%;
  background-repeat: no-repeat;
  background-position: 100% center;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: op-text-shimmer 2.6s linear infinite;
  will-change: background-position;
}

.timeline-summary .op-label.op-shimmer {
  --op-shimmer-base: var(--sun-text-secondary);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text-secondary) 32%, white);
}

@keyframes op-text-shimmer {
  0% { background-position: 100% center; }
  100% { background-position: 0% center; }
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
