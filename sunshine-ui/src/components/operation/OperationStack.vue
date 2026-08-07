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
  catalogToolIdFromStepId,
  isSandboxExecStep,
  sandboxToolKind,
} from '../../api/processingStepsDisplay'
import { isThinkStepId } from '../../api/processingStepsNormalize'
import {
  isRagStepId,
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
  shouldShowAfterThinkPendingHint,
  shouldShowPendingHintForLastRow,
} from '../../api/contentInterleave'
import OperationCard from './OperationCard.vue'
import TaskBoardPanel from './TaskBoardPanel.vue'
import SubagentCard from './SubagentCard.vue'
import ToolGroupCard from './ToolGroupCard.vue'
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
  /** assistant 消息 id */
  messageId?: string
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

/** 存在等待用户确认的 HITL 步（tool 或 plan node）→ 折叠态会隐藏确认框，须强制展开避免写操作阻塞不可达 */
const hasAwaitingHitlStep = computed(() =>
  props.steps.some(step => isHitlAwaiting(step) || isHitlSummaryAwaiting(step)),
)

const timelineBodyExpanded = computed(() => {
  if (!summaryEnabled.value) return true
  if (hasAwaitingHitlStep.value) return true
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

/** 正文（message.content / contentBlocks）最近一次流式增长的时间戳。
 * 用于区分「整文正在流式输出」（正文逐字增长，用户可见反馈，无需占位）
 * 与「正文已输出完、模型在生成下一步 tool 参数」（正文静止，需占位提示仍在工作）。 */
const lastContentGrowthAt = ref(0)

watch(
  () => props.messageContent,
  () => {
    if (isTimelineProcessing.value) lastContentGrowthAt.value = Date.now()
  },
)

/** 正文是否正在流式增长（2s 窗口）：整文输出中 → 抑制空档占位，避免与正文并存 */
const isContentGrowthActive = computed(() =>
  isTimelineProcessing.value && nowMs.value - lastContentGrowthAt.value < 2000,
)

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

/** 显示行：普通步骤或工具/检索组（连续的同类步折叠为一组） */
type DisplayRow =
  | { kind: 'step'; step: ProcessingStep }
  | {
      kind: 'toolGroup'
      groupKind: ToolGroupKind
      steps: ProcessingStep[]
      allDone: boolean
      anyRunning: boolean
    }

/** 组类别：普通工具调用 / 知识检索 / sandbox 按用途细分 */
type ToolGroupKind =
  | 'tool'
  | 'rag'
  | 'sandbox-view'
  | 'sandbox-edit'
  | 'sandbox-fetch'
  | 'sandbox-exec'

/** 步可折叠分组的类别；其余返回 null 单独成行。
 * rag 步须在 tool 前判定——后端将 rag 归入 tool 步（phase=tool），但按 id 精确区分。
 * sandbox 工具按用途细分，同类别可混组（如 read/glob/grep 归「查看」）。 */
function toolGroupKind(step: ProcessingStep): ToolGroupKind | null {
  if (isRagStepId(step.id)) return 'rag'
  if (!isToolStepId(step.id) || step.phase !== 'tool') return null
  const toolId = catalogToolIdFromStepId(step.id)
  const sandboxKind = sandboxToolKind(toolId)
  if (sandboxKind) return `sandbox-${sandboxKind}` as ToolGroupKind
  return 'tool'
}

/** 将连续同类的 tool / rag 步各分为一组；HITL awaiting 中的工具步单独成行保持内联确认框可见 */
function groupToolSteps(steps: ProcessingStep[]): DisplayRow[] {
  const rows: DisplayRow[] = []
  let i = 0
  while (i < steps.length) {
    const s = steps[i]
    const groupKind = toolGroupKind(s)
    if (!groupKind) {
      rows.push({ kind: 'step', step: s })
      i++
      continue
    }
    // 收集连续的同类步（tool 与 rag 不混组）
    const group: ProcessingStep[] = []
    while (i < steps.length && toolGroupKind(steps[i]) === groupKind) {
      group.push(steps[i])
      i++
    }
    if (group.length === 1) {
      rows.push({ kind: 'step', step: group[0] })
    } else {
      const allDone = group.every(s => s.lifecycle === 'done' || s.lifecycle === 'skipped' || s.lifecycle === 'error')
      const anyRunning = group.some(s => s.lifecycle === 'running')
      rows.push({ kind: 'toolGroup', groupKind, steps: group, allDone, anyRunning })
    }
  }
  return rows
}

const displayRows = computed(() => groupToolSteps(displaySteps.value))

/** exec 步所属轮次 think 摘要（think_summary 工具输出）：exec 步向前取最近 think 步的 stepSummary。
 * 主行显示「执行命令 {摘要} {命令头}」，摘要缺失则仅命令头。 */
const thinkSummaryByStepId = computed(() => {
  const map = new Map<string, string>()
  let current = ''
  for (const s of displaySteps.value) {
    if (isThinkStepId(s.id)) {
      if (s.stepSummary?.trim()) current = s.stepSummary.trim()
    } else if (isSandboxExecStep(s)) {
      if (current) map.set(s.id, current)
    }
  }
  return map
})

/** 折叠态常驻的 taskboard 步（若有真实任务项）：生成 todolist 后即便后续还有 think/tool 步，
 * 折叠时间线时仍在概要区露出，不被最后一条 preview 步顶掉 */
const collapsedTaskBoardStep = computed(() => {
  if (!summaryEnabled.value || timelineBodyExpanded.value) return undefined
  return displaySteps.value.find(s => s.phase === 'tasks' && hasRealTaskBoardItems(s))
})

/** 正在处理且整段折叠：只露 displaySteps 最后一条（折叠概要，无穿插正文）。
 * taskboard 步已由 collapsedTaskBoardStep 单独常驻，此处跳过 tasks，避免与常驻面板重复 */
const collapsedPreviewStep = computed(() => {
  if (!summaryEnabled.value || timelineBodyExpanded.value || !isTimelineProcessing.value) {
    return undefined
  }
  const steps = displaySteps.value.filter(s => s.phase !== 'tasks')
  return steps.length ? steps[steps.length - 1] : undefined
})

/** 正在处理折叠：在步骤概要下继续展示最后一段正文（contentBlocks） */
const showProcessingCollapsedAnswer = computed(() =>
  !!collapsedPreviewStep.value && !!collapsedAnswerText.value,
)

/** 折叠态执行空档占位：正在处理、最后可见步已终态时展示三点，
 * 覆盖模型生成 tool 参数（写大文件等）或下一轮推理的长空档，防止用户误以为卡死。
 * 折叠区展示的历史正文不抑制占位——占位表示「还有内容在生成」；
 * 但整文正在流式输出时正文本身是反馈，不显示占位 */
const showCollapsedPendingHint = computed(() => {
  if (isContentGrowthActive.value) return false
  const step = collapsedPreviewStep.value
  if (!step || timelineBodyExpanded.value) return false
  return shouldShowAfterThinkPendingHint({
    processing: isTimelineProcessing.value,
    lastStep: step,
  })
})

const pendingList = computed(() =>
  normalizePendingHitlList(props.pendingHitlConfirmations),
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

/** 占位伪 think 步：复用 OperationCard 渲染「正在执行」，与「深度思考」行结构/流光完全一致。
 * id 以 think- 开头走 isThinkStepId；stepSummary 承载占位文案；clientStartedAt 驱动运行时长 */
const placeholderStartedAt = ref(0)
const placeholderStep = computed<ProcessingStep>(() => ({
  id: 'think-__pending',
  phase: 'think',
  lifecycle: 'running',
  stepSummary: '正在执行',
  clientStartedAt: placeholderStartedAt.value,
}))

/** 执行空档占位：末尾是已完成（done/error/skipped）的可见步时展示。
 * 末尾为工具组时取组内最后一步判定（组内仍有运行中步自带 pulse 不提示）。
 * 整文正在流式输出时正文本身是反馈，不显示占位 */
const showAfterThinkPendingHint = computed(() => {
  void props.timelineRevision
  if (isContentGrowthActive.value) return false
  const rows = displayRows.value
  const last = rows.length ? rows[rows.length - 1] : undefined
  if (!last) return false
  return shouldShowPendingHintForLastRow({
    processing: isTimelineProcessing.value,
    lastRow: last,
  })
})

watch([showCollapsedPendingHint, showAfterThinkPendingHint], () => {
  if (showCollapsedPendingHint.value || showAfterThinkPendingHint.value) {
    placeholderStartedAt.value = Date.now()
  }
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
        <span class="op-main">
          <span
            class="op-label"
            :class="{ 'op-shimmer': live || messageStatus === 'streaming' }"
          >{{ summaryText }}</span>
          <svg
            class="op-chevron"
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2.5"
            stroke-linecap="round"
            aria-hidden="true"
          >
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </span>
      </button>
    </div>

    <template v-if="timelineBodyExpanded">
      <template
        v-for="(row, rowIdx) in displayRows"
        :key="row.kind === 'toolGroup' ? `tg-${row.steps.map(s => s.id).join('|')}` : `row-${rowIdx}`"
      >
        <div class="op-row">
        <template v-if="row.kind === 'toolGroup'">
          <ToolGroupCard
            :group-kind="row.groupKind"
            :steps="row.steps"
            :all-done="row.allDone"
            :any-running="row.anyRunning"
            :live="live"
            :pending-list="pendingList"
            :all-steps="effectiveSteps"
            :summary-by-step-id="thinkSummaryByStepId"
            @hitl-decided="(token, approved) => emit('hitlDecided', token, approved)"
          />
        </template>
        <template v-else>
        <template v-for="step in [row.step]" :key="`${step.id}-${hitlRevision}-${step.summary?.active ?? ''}`">
        <PlanWorkflowPanel
          v-if="step.phase === 'plan' && showPlanDag"
          :plan-step="step"
          :all-steps="effectiveSteps"
          :live="live"
          :execution-plan-id="executionPlanId"
          :user-query="userQuery"
          :pending-hitl-confirmations="pendingList"
        />
        <TaskBoardPanel
          v-else-if="step.phase === 'tasks'"
          :data-live-taskboard="live && lifecycleOf(step) === 'running' ? '1' : undefined"
          :step="step"
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
            :round-summary="thinkSummaryByStepId.get(step.id)"
            @toggle="toggleCard(step)"
          />
          <div v-if="shouldShowInlineHitl(step)" class="op-line-hitl">
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
              :pending-hitl-confirmations="pendingList"
              @hitl-decided="(token, approved) => emit('hitlDecided', token, approved)"
            />
          </div>
        </template>
        <!-- Plan DAG 下 node-answer 正文锚定到 plan，须在 PlanWorkflowPanel 之后渲染 -->
        <template v-for="crow in rowsAfterStep(step.id)" :key="crow.key">
          <div class="op-inline-content">
            <div class="op-inline-body" :class="{ 'is-streaming-md': crow.streaming }">
              <StaticMarkdown :source="crow.text" :defer-mermaid="crow.streaming" />
            </div>
          </div>
        </template>
        </template>
        </template>
        </div>
      </template>
      <template v-for="row in orphanContent" :key="row.key">
        <div class="op-inline-content">
          <div class="op-inline-body" :class="{ 'is-streaming-md': row.streaming }">
            <StaticMarkdown :source="row.text" :defer-mermaid="row.streaming" />
          </div>
        </div>
    </template>
    <!-- think 步后、工具出现前的过渡提示：工具步出现或消息终态后自动消失 -->
    <!-- think 完成后的执行空档占位：「正在执行」复用 OperationCard（与「深度思考」行结构一致），工具步出现后消失 -->
    <OperationCard
      v-if="showAfterThinkPendingHint"
      :step="placeholderStep"
      :expanded="false"
      :live="true"
      :hide-chevron="true"
      :embed-hitl="false"
      class="op-pending-hint"
    />
  </template>
    <!-- 折叠态常驻 taskboard：生成 todolist 后折叠时间线仍可见（进行中/终态均露出） -->
    <!-- 折叠态常驻 taskboard：生成 todolist 后折叠时间线仍可见（进行中/终态均露出） -->
    <TaskBoardPanel
      v-if="!timelineBodyExpanded && collapsedTaskBoardStep"
      :data-live-taskboard="live && lifecycleOf(collapsedTaskBoardStep) === 'running' ? '1' : undefined"
      :step="collapsedTaskBoardStep"
      :live="live && lifecycleOf(collapsedTaskBoardStep) === 'running'"
    />
    <!-- 正在处理折叠：最后一步概要（无 chevron）+ 最后一段正文 -->
    <template v-if="!timelineBodyExpanded && collapsedPreviewStep">
      <PlanWorkflowPanel
        v-if="collapsedPreviewStep.phase === 'plan' && showPlanDag"
        :plan-step="collapsedPreviewStep"
        :all-steps="effectiveSteps"
        :live="live"
        :execution-plan-id="executionPlanId"
        :user-query="userQuery"
        :pending-hitl-confirmation="pendingList"
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
        :round-summary="thinkSummaryByStepId.get(collapsedPreviewStep.id)"
      />
      <div
        v-if="showProcessingCollapsedAnswer"
        class="op-inline-content timeline-collapsed-answer"
      >
        <div class="op-inline-body" :class="{ 'is-streaming-md': streamLive || live }">
          <StaticMarkdown
            :source="collapsedAnswerText"
            :defer-mermaid="!!(streamLive || live)"
          />
        </div>
      </div>
      <!-- 折叠态执行空档占位：正在处理且最后可见步已终态时显示「正在执行」，工具/正文出现后自动消失 -->
      <OperationCard
        v-if="showCollapsedPendingHint"
        :step="placeholderStep"
        :expanded="false"
        :live="true"
        :hide-chevron="true"
        :embed-hitl="false"
        class="op-pending-hint"
      />
    </template>
    <div
      v-else-if="!timelineBodyExpanded && showCollapsedAnswer"
      class="op-inline-content timeline-collapsed-answer"
    >
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
  gap: 0;
  padding: 0 0 12px;
}

.op-row {
  min-width: 0;
}

/* 行间距统一走 margin-top 单侧 8px（flex 中 margin 不折叠，避免 gap+margin 叠加）；
   HITL 与折叠内工具调用不加行间距（折叠外的工具组行由 .op-row 承担） */
.op-row + .op-row {
  margin-top: 8px;
}

.timeline-summary {
  font-size: var(--sun-font-md);
  line-height: 1.5;
  color: var(--sun-text-muted);
  margin-bottom: 4px;
}

.timeline-summary .op-line-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
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

/* 文字后展开箭头：紧跟概要文字，折叠 > 展开 ^；尺寸加大更明显 */
.timeline-summary .op-chevron {
  flex-shrink: 0;
  align-self: center;
  width: 12px;
  height: 12px;
  color: var(--sun-text-secondary);
  opacity: 0.85;
  margin-left: 2px;
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
  animation: op-text-shimmer 1.2s linear infinite;
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
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
}

.op-inline-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  /* 阶段正文：与 card 间距 8px；下方间距由下一行的 margin-top 承担，避免叠加 */
  margin: 8px 0 0;
}

.op-nested-stack {
  margin: 2px 0 8px 16px;
  padding-left: 8px;
  border-left: 1px solid var(--sun-border);
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

/* 执行空档占位（复用 OperationCard 渲染，与「深度思考」行结构一致）：上侧边距与相邻卡片一致 */
.op-pending-hint {
  margin-top: 8px;
}

.op-line-hitl :deep(.collapsible-confirm) {
  margin-left: 0;
}
</style>
