<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { liveTimelineExpanded } from '../../composables/timelineCollapseBus'
import type { ProcessingStep, TimelineMessageStatus } from '../../api/processingSteps'
import {
  formatElapsedClock,
  formatTimelineSummaryText,
  hasRealTaskBoardItems,
  isDecisionStep,
  isSubagentStep,
  resolveTimelineElapsedMs,
  resolveTimelineSummaryPrefix,
} from '../../api/processingSteps'
import { isHarnessTimelineMessage, isPlanDagMessage } from '../../api/harnessTimeline'
import {
  isHarnessPlanStep,
  isWorkerStep,
} from '../../api/harnessHierarchy'
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
import WorkerCard from './WorkerCard.vue'
import DecisionCard from './DecisionCard.vue'
import ToolGroupCard from './ToolGroupCard.vue'
import HitlStepActions from './HitlStepActions.vue'
import PlanDagPanel from '../plan/PlanDagPanel.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import { ensurePlanTimelineSteps, isPlanDagNodeStep } from '../../api/planHydrate'
import TimelineStepIcon from './TimelineStepIcon.vue'
import { useTimelineStyle } from '../../composables/useTimelineStyle'

const props = withDefaults(defineProps<{
  steps: ProcessingStep[]
  live?: boolean
  /** ChatView 底部折叠气泡的请求计数（仅运行中最后一条消息传入）；变化时折叠展开的时间线 */
  collapseTick?: number
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
  /** 与 cancelSpawnSubagent 同源：active session / active-generation */
  generationId?: string
}>(), {
  embedHitl: true,
  inlineHitl: true,
  contentBlocks: undefined,
  streamLive: false,
  collapseTick: undefined,
  generationId: '',
})

const emit = defineEmits<{
  hitlDecided: [token: string, approved: boolean]
}>()

const { timelineStyle } = useTimelineStyle()

const cardExpanded = reactive(new Map<string, boolean>())
const cardUserToggled = reactive(new Set<string>())
const roundGroupExpanded = reactive(new Set<number>())
/** roundGroup 内 OperationCard 的展开状态 */
const roundGroupCardExpanded = reactive(new Map<string, boolean>())

function isRoundGroupExpanded(idx: number): boolean {
  return roundGroupExpanded.has(idx)
}

function toggleRoundGroup(idx: number): void {
  if (roundGroupExpanded.has(idx)) roundGroupExpanded.delete(idx)
  else roundGroupExpanded.add(idx)
}

const summaryEnabled = computed(() => props.messageStatus !== undefined)

const timelineUserToggled = ref(false)
const timelineExpandedOverride = ref(false)

/** 存在等待用户确认的 HITL / decision 步 → 折叠态会隐藏交互框，须强制展开 */
const hasAwaitingHitlStep = computed(() =>
  props.steps.some(step =>
    isHitlAwaiting(step)
    || isHitlSummaryAwaiting(step)
    || (isDecisionStep(step) && lifecycleOf(step) === 'awaiting'),
  ),
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

/**
 * 悬浮 taskboard 观测锚点：Chat MAIN（todo_write running）或 pro/harness
 * （emitTaskBoardSnapshot 快照步 lifecycle 恒为 done）有真实任务项时都参与悬浮。
 */
function isLiveTaskboardAnchor(step: ProcessingStep | undefined): boolean {
  if (!props.live || !step) return false
  if (lifecycleOf(step) === 'running') return true
  return isHarnessTimeline.value && hasRealTaskBoardItems(step)
}

function isCardExpanded(step: ProcessingStep): boolean {
  if (cardUserToggled.has(step.id)) {
    return cardExpanded.get(step.id) ?? false
  }
  // loop 内过程：运行中默认展开，便于看流式 think/正文；结束后默认收起
  if (hasNestedBodyTimeline(step)) {
    return lifecycleOf(step) === 'running'
  }
  return false
}

function hasNestedLoopBodyTimeline(step: ProcessingStep): boolean {
  return !!step.id?.startsWith('i')
    && !!(step.subSteps?.length || step.contentBlocks?.length)
}

/** OperationCard 兜底分支内嵌展开仅服务 loop（worker 已迁移 WorkerCard → PlanNodeDrawer） */
function hasNestedBodyTimeline(step: ProcessingStep): boolean {
  return hasNestedLoopBodyTimeline(step)
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

/** 流式静默多久后才露出空档三点（避免 think/tool 间隙频繁闪现） */
const STREAM_IDLE_DOTS_MS = 2000

/** 最近一次流式活动时间（正文增长 / timelineRevision / 进入 processing）。
 * 只盯总字符数与 revision，避免每 token join 全文。 */
const lastStreamActivityAt = ref(0)

function markStreamActivity(): void {
  if (isTimelineProcessing.value) lastStreamActivityAt.value = Date.now()
}

watch(
  () => {
    let chars = props.messageContent?.length ?? 0
    const blocks = props.contentBlocks
    if (blocks) {
      for (let i = 0; i < blocks.length; i++) chars += blocks[i].text?.length ?? 0
    }
    return chars
  },
  () => { markStreamActivity() },
)

watch(
  () => props.timelineRevision,
  () => { markStreamActivity() },
)

watch(
  isTimelineProcessing,
  (processing) => {
    if (processing) lastStreamActivityAt.value = Date.now()
  },
)

/** 近 2s 内仍有流式活动：抑制空档三点 */
const isStreamRecentlyActive = computed(() =>
  isTimelineProcessing.value
  && lastStreamActivityAt.value > 0
  && nowMs.value - lastStreamActivityAt.value < STREAM_IDLE_DOTS_MS,
)

function hasRunningOperationalStep(): boolean {
  return props.steps.some((s) => {
    const lc = s.lifecycle ?? 'pending'
    if (lc !== 'running') return false
    if (s.phase === 'generate') return false
    return true
  })
}

const hasAssistantContentText = computed(() =>
  !!(props.messageContent?.trim()
    || props.contentBlocks?.some(b => !!b.text?.trim())),
)

/**
 * 终稿阶段空档三点：已有正文、无运行步、且流式已静默 ≥2s。
 * 刷字/步骤更新期间只靠既有反馈；静默后的空档与中间 pending hint 共用三点。
 */
const showFinalAnswerDots = computed(() => {
  if (!isTimelineProcessing.value) return false
  if (isStreamRecentlyActive.value) return false
  if (hasRunningOperationalStep()) return false
  return hasAssistantContentText.value
})

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

/** 总览时钟或 processing 空档判定都依赖 nowMs 推进（满 2s 静默才露三点） */
watch(
  () => summaryClockLive.value || isTimelineProcessing.value,
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

const showPlanDag = computed(() =>
  isPlanDagMessage(effectiveSteps.value, props.executionPlanId),
)

const isHarnessTimeline = computed(() =>
  isHarnessTimelineMessage(effectiveSteps.value, props.executionPlanId),
)

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
  // harness / ReAct：保留 worker-* 与 Planner 元工具调用（plan_submit / self_assess / dispatch_worker
  // 由 ProcessingStepMiddleware 已映射为对应 step，不再单独成行）；
  // 正文已 inline 穿插，隐藏 generate；无 items 的 tasks 占位不展示。
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
  | {
      kind: 'roundGroup'
      label: string
      rows: DisplayRow[]
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

/** harness（pro）时间线：所有过程步/工具步平铺不折叠；其余模式连续同类工具折叠成组 */
const displayRows = computed(() => {
  if (isHarnessTimeline.value) {
    return displaySteps.value.map(step => ({ kind: 'step', step }) as DisplayRow)
  }
  return groupToolSteps(displaySteps.value)
})

/** 头部钉扎步不进入 roundGroup：intent → skill → tasks */
function isPinnedHeaderPhase(phase: string | undefined): boolean {
  return phase === 'intent' || phase === 'skill' || phase === 'tasks'
}

function pinnedHeaderOrder(phase: string | undefined): number {
  if (phase === 'intent') return 0
  if (phase === 'skill') return 1
  if (phase === 'tasks') return 2
  return 3
}

/** round-group 预处理：抽出 intent/skill/tasks，剩余步才参与折叠 */
function processRoundSegment(steps: ProcessingStep[]): { separates: DisplayRow[]; remaining: ProcessingStep[] } {
  const separates: DisplayRow[] = []
  const remaining: ProcessingStep[] = []
  for (const s of steps) {
    if (isPinnedHeaderPhase(s.phase)) {
      separates.push({ kind: 'step', step: s })
    } else {
      remaining.push(s)
    }
  }
  // 即使输入时间戳乱序，头部仍按 intent → skill → tasks 展示
  separates.sort((a, b) => {
    if (a.kind !== 'step' || b.kind !== 'step') return 0
    return pinnedHeaderOrder(a.step.phase) - pinnedHeaderOrder(b.step.phase)
  })
  return { separates, remaining }
}

/** 构建 roundGroup 折叠标签：
 * 统计折叠区内除 intent/tasks/首 think / subagent / decision 外的操作 */
function buildRoundGroupLabel(_collapsedRounds: DisplayRow[][], flatCollapsed: DisplayRow[]): string {
  const keyCounts = new Map<string, number>()
  const keyLabel = new Map<string, string>()

  function add(key: string, verb: string, noun: string) {
    keyCounts.set(key, (keyCounts.get(key) ?? 0) + 1)
    const n = keyCounts.get(key)!
    keyLabel.set(key, `${verb}${n}次${noun}`)
  }

  function countStep(s: ProcessingStep) {
    if (isSubagentStep(s) || isDecisionStep(s)) return
    const cid = catalogToolIdFromStepId(s.id)
    if (cid?.startsWith('sandbox__')) {
      switch (cid) {
        case 'sandbox__read':
        case 'sandbox__glob':       add('view', '查询', '文件'); break
        case 'sandbox__grep':       add('grep', '搜索', '内容'); break
        case 'sandbox__edit':
        case 'sandbox__write':      add('edit', '修改', '文件'); break
        case 'sandbox__webfetch':
        case 'sandbox__websearch':  add('web', '搜索', '网页'); break
        default:                    add('exec', '执行', '命令'); break
      }
      return
    }
    if (isRagStepId(s.id)) { add('rag', '检索', '知识库'); return }
    add('tool', '调用', '工具')
  }

  let firstThinkSkipped = false
  for (const row of flatCollapsed) {
    if (row.kind === 'toolGroup') {
      for (const s of row.steps) countStep(s)
    } else if (row.kind === 'step') {
      if (isPinnedHeaderPhase(row.step.phase)) continue
      if (isThinkStepId(row.step.id) && !firstThinkSkipped) {
        firstThinkSkipped = true
        continue
      }
      countStep(row.step)
    }
  }

  const parts: string[] = []
  for (const key of keyCounts.keys()) {
    parts.push(keyLabel.get(key)!)
  }
  return parts.join('、')
}

/**
 * 折叠区写入：至少 2 行才包 roundGroup。
 * 抽出 think1 后若只剩 1 个 toolGroup，包一层会变成「调用N次工具」套「调用N个工具」。
 */
function pushCollapsedOperationRows(result: DisplayRow[], collapsedRows: DisplayRow[]): void {
  if (collapsedRows.length === 0) return
  if (collapsedRows.length === 1) {
    result.push(collapsedRows[0])
    return
  }
  const allDone = collapsedRows.every(r => r.kind === 'step' ? (r.step.lifecycle ?? 'pending') === 'done' : r.allDone)
  const anyRunning = collapsedRows.some(r => r.kind === 'step' ? (r.step.lifecycle ?? 'pending') === 'running' : r.anyRunning)
  result.push({
    kind: 'roundGroup',
    label: buildRoundGroupLabel([collapsedRows], collapsedRows),
    rows: collapsedRows,
    allDone,
    anyRunning,
  })
}

/** subagent / decision / harness plan·worker 须始终露出主时间线，不可被 roundGroup 吞掉 */
function isTimelineStickyStep(step: ProcessingStep): boolean {
  if (isSubagentStep(step) || isDecisionStep(step)) return true
  if (isHarnessTimeline.value && (isWorkerStep(step) || isHarnessPlanStep(step))) return true
  return false
}

/** 按原序：sticky 步单独露出，其余可折叠段再 roundGroup */
function pushCollapsedWithSticky(result: DisplayRow[], rows: DisplayRow[]): void {
  let buffer: DisplayRow[] = []
  const flush = () => {
    pushCollapsedOperationRows(result, buffer)
    buffer = []
  }
  for (const row of rows) {
    if (row.kind === 'step' && isTimelineStickyStep(row.step)) {
      flush()
      result.push(row)
    } else {
      buffer.push(row)
    }
  }
  flush()
}

/** 正文间多轮操作折叠：以 ContentBlock 为段边界；无正文时整段视为单一操作段，
 * 达到折叠条件即收起（不必等首段正文出现）。段内若 grouped 行数 >=2 且 think 不足 2 个时，
 * 折叠除最后一行外的多余行；think >=2 按原有轮次折叠。
 * intent/skill/tasks 与 think1（整个时间线首个 think）始终不进入折叠。
 * 折叠区不足 2 行时不包 roundGroup（避免单 toolGroup 双层折叠）。 */
function roundGroupSteps(inputRows: DisplayRow[]): DisplayRow[] {
  // 收集 contentBlock stepping 信息；无正文 → 空集 → 整段单 segment 仍走折叠
  const blockAfterStepIds = new Set<string>()
  for (const cb of props.contentBlocks ?? []) {
    if (cb.afterStepId) blockAfterStepIds.add(cb.afterStepId)
  }

  // 以正文分隔为边界切分步骤段
  const segments: DisplayRow[][] = []
  let current: DisplayRow[] = []
  for (const row of inputRows) {
    const stepId = row.kind === 'step' ? row.step.id : row.kind === 'toolGroup' ? row.steps[row.steps.length - 1]?.id : undefined
    current.push(row)
    if (stepId && blockAfterStepIds.has(stepId)) {
      segments.push(current)
      current = []
    }
  }
  if (current.length) segments.push(current)

  // 找出整个时间线第一个 think 步（think1 不进入任何折叠）
  let think1Id: string | undefined
  for (const row of inputRows) {
    if (row.kind === 'step' && isThinkStepId(row.step.id)) {
      think1Id = row.step.id
      break
    }
  }

  const result: DisplayRow[] = []
  for (const seg of segments) {
    const steps: ProcessingStep[] = []
    for (const r of seg) {
      if (r.kind === 'toolGroup') steps.push(...r.steps)
      else if (r.kind === 'step') steps.push(r.step)
    }
    const { separates, remaining } = processRoundSegment(steps)
    result.push(...separates)

    const grouped = groupToolSteps(remaining)
    const thinkIndices: number[] = []
    grouped.forEach((r, i) => {
      if (r.kind === 'step' && isThinkStepId(r.step.id)) thinkIndices.push(i)
    })

    if (thinkIndices.length >= 2) {
      // >=2 thinks：前 N-1 轮折叠，think1 不进入
      const lastThinkIdx = thinkIndices[thinkIndices.length - 1]
      const collapsedRows = grouped.slice(0, lastThinkIdx)
      if (think1Id) {
        const t1Idx = collapsedRows.findIndex(r => r.kind === 'step' && r.step.id === think1Id)
        if (t1Idx >= 0) result.push(collapsedRows.splice(t1Idx, 1)[0])
      }
      pushCollapsedWithSticky(result, collapsedRows)
      result.push(...grouped.slice(lastThinkIdx))
    } else if ((thinkIndices.length >= 1 && grouped.length >= 3) || (thinkIndices.length === 0 && grouped.length >= 2)) {
      // 1 think + >=2 散列 tool（或 0 think + >=2 散列 tool）：折叠多余行，仅保留最后一行可见
      const collapsedRows = [...grouped.slice(0, -1)]
      if (think1Id) {
        const t1Idx = collapsedRows.findIndex(r => r.kind === 'step' && r.step.id === think1Id)
        if (t1Idx >= 0) result.push(collapsedRows.splice(t1Idx, 1)[0])
      }
      pushCollapsedWithSticky(result, collapsedRows)
      result.push(grouped[grouped.length - 1])
    } else {
      result.push(...grouped)
    }
  }

  return result
}

/** 显示行（带正文间多轮折叠）；harness 分层时间线不做 roundGroup，避免吞掉 plan/worker */
const roundDisplayRows = computed(() => {
  if (isHarnessTimeline.value) return displayRows.value
  return roundGroupSteps(displayRows.value)
})

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

/** 折叠态执行空档占位：中间/终稿空档统一三点（流式静默未满 2s 不显示） */
const showCollapsedPendingHint = computed(() => {
  if (isStreamRecentlyActive.value) return false
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
    props.executionPlanId,
  )
}

/** toolGroup / roundGroup 内步骤也会锚定正文；展开态须在组后穿插，否则终稿只出现在折叠预览 */
function stepIdsInDisplayRow(row: DisplayRow): string[] {
  if (row.kind === 'step') return [row.step.id]
  if (row.kind === 'toolGroup') return row.steps.map(s => s.id)
  const ids: string[] = []
  for (const inner of row.rows) ids.push(...stepIdsInDisplayRow(inner))
  return ids
}

function rowsAfterDisplayRow(row: DisplayRow) {
  void props.timelineRevision
  const rows: ReturnType<typeof rowsAfterStep> = []
  for (const id of stepIdsInDisplayRow(row)) {
    rows.push(...rowsAfterStep(id))
  }
  return rows
}

const orphanContent = computed(() => {
  void props.timelineRevision
  return orphanContentRows(
    props.steps,
    visibleStepIds.value,
    props.contentBlocks,
    contentRowOpts.value,
    props.executionPlanId,
  )
})

/** 执行空档占位：中间/终稿空档统一三点，流式静默未满 2s 不显示 */
const showAfterThinkPendingHint = computed(() => {
  void props.timelineRevision
  if (isStreamRecentlyActive.value) return false
  const rows = roundDisplayRows.value
  const last = rows.length ? rows[rows.length - 1] : undefined
  if (!last) return false
  // 若是 roundGroup，取其内部最后一个非 roundGroup 行
  let effectiveLast = last
  while (effectiveLast.kind === 'roundGroup' && effectiveLast.rows.length) {
    const inner = effectiveLast.rows[effectiveLast.rows.length - 1]
    if (inner.kind === 'roundGroup') effectiveLast = inner
    else { effectiveLast = inner; break }
  }
  if (effectiveLast.kind === 'roundGroup') return false
  return shouldShowPendingHintForLastRow({
    processing: isTimelineProcessing.value,
    lastRow: effectiveLast,
  })
})

/** 展开态空档三点：中间 pending 或终稿空档 */
const showExpandedPendingDots = computed(() =>
  showFinalAnswerDots.value || showAfterThinkPendingHint.value,
)

/** 折叠态空档三点 */
const showCollapsedPendingDots = computed(() =>
  showFinalAnswerDots.value || showCollapsedPendingHint.value,
)

/** 折叠请求：ChatView 底部折叠气泡点击时自增，仅传入 tick 的 live 实例响应（无待确认 HITL 时 no-op） */
watch(
  () => props.collapseTick,
  () => {
    if (props.collapseTick == null) return
    if (hasAwaitingHitlStep.value || !timelineBodyExpanded.value) return
    timelineUserToggled.value = true
    timelineExpandedOverride.value = false
  },
)

/** 展开态上报：仅 tick 实例参与；tick 消失（运行结束 / 换消息）时复位，避免折叠气泡残留 */
let prevCollapseTick: number | undefined
watch(
  () => [props.collapseTick, timelineBodyExpanded.value] as const,
  ([tick, expanded]) => {
    const wasTick = prevCollapseTick != null
    prevCollapseTick = tick
    if (tick != null) liveTimelineExpanded.value = expanded
    else if (wasTick) liveTimelineExpanded.value = false
  },
  { immediate: true },
)
</script>

<template>
  <div
    class="operation-lines"
    :class="{ 'is-timeline-standard': timelineStyle === 'standard' }"
  >
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
          <span v-if="timelineStyle === 'standard'" class="op-step-icon">
            <TimelineStepIcon
              v-if="effectiveSteps.length"
              class="op-type-icon"
              symbol="summary"
            />
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
          <span
            class="op-label"
            :class="{ 'op-shimmer': live || messageStatus === 'streaming' }"
          >{{ summaryText }}</span>
          <svg
            v-if="timelineStyle !== 'standard'"
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
        v-for="(row, rowIdx) in roundDisplayRows"
        :key="row.kind === 'toolGroup' ? `tg-${row.steps.map(s => s.id).join('|')}` : row.kind === 'roundGroup' ? `rg-${rowIdx}` : `row-${rowIdx}`"
      >
        <!-- 正文间多轮折叠：显示 >1 轮 = 自动折叠，仅保留最后一轮可见 -->
        <template v-if="row.kind === 'roundGroup'">
          <div class="op-row">
            <div class="round-group" :class="{ 'is-expanded': isRoundGroupExpanded(rowIdx), 'is-running': row.anyRunning }">
              <div
                class="round-group-row"
                role="button"
                tabindex="0"
                @click="toggleRoundGroup(rowIdx)"
                @keydown.enter.prevent="toggleRoundGroup(rowIdx)"
                @keydown.space.prevent="toggleRoundGroup(rowIdx)"
              >
                <span class="op-main">
                  <span v-if="timelineStyle === 'standard'" class="op-step-icon">
                    <TimelineStepIcon
                      class="op-type-icon"
                      symbol="round"
                    />
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
                  <span class="round-group-label" :class="{ 'op-shimmer': row.anyRunning && live }">{{ row.label }}</span>
                  <span v-if="row.allDone" class="op-check" aria-label="完成">
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5" />
                      <polyline points="4.5 8 7 10.5 11.5 5.5" />
                    </svg>
                  </span>
                  <svg
                    v-if="timelineStyle !== 'standard'"
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
              </div>
              <div v-if="isRoundGroupExpanded(rowIdx)" class="round-group-body">
                <template v-for="(inner, ii) in row.rows" :key="inner.kind === 'toolGroup' ? `tg-${inner.steps.map(s => s.id).join('|')}` : `sg-${ii}`">
                  <template v-if="inner.kind === 'toolGroup'">
                    <ToolGroupCard
                      :group-kind="inner.groupKind"
                      :steps="inner.steps"
                      :all-done="inner.allDone"
                      :any-running="inner.anyRunning"
                      :live="false"
                      :hide-checkmark="true"
                      :pending-list="pendingList"
                      :all-steps="effectiveSteps"
                      :summary-by-step-id="thinkSummaryByStepId"
                      @hitl-decided="(token, approved) => emit('hitlDecided', token, approved)"
                    />
                  </template>
                  <template v-else-if="inner.kind === 'step' && isSubagentStep(inner.step)">
                    <SubagentCard
                      :step="inner.step"
                      :live="false"
                    />
                  </template>
                  <template v-else-if="inner.kind === 'step' && isWorkerStep(inner.step)">
                    <WorkerCard
                      :step="inner.step"
                      :live="false"
                    />
                  </template>
                  <template v-else-if="inner.kind === 'step' && isDecisionStep(inner.step)">
                    <DecisionCard
                      :step="inner.step"
                      :live="false"
                      :generation-id="generationId"
                    />
                  </template>
                  <template v-else-if="inner.kind === 'step'">
                    <OperationCard
                      :step="inner.step"
                      :expanded="roundGroupCardExpanded.get(inner.step.id) ?? false"
                      :live="false"
                      :embed-hitl="false"
                      :hide-checkmark="true"
                      :round-summary="thinkSummaryByStepId.get(inner.step.id)"
                      @toggle="roundGroupCardExpanded.set(inner.step.id, !(roundGroupCardExpanded.get(inner.step.id) ?? false))"
                    />
                  </template>
                </template>
              </div>
            </div>
          </div>
          <!-- 组内工具常锚定终稿；组折叠时也要在组后露出正文 -->
          <template v-for="crow in rowsAfterDisplayRow(row)" :key="crow.key">
            <div class="op-inline-content">
              <div class="op-inline-body" :class="{ 'is-streaming-md': crow.streaming }">
                <StaticMarkdown :source="crow.text" :defer-mermaid="crow.streaming" :streaming="crow.streaming" />
              </div>
            </div>
          </template>
        </template>
        <template v-else>
        <div
          class="op-row"
        >
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
          <template v-for="crow in rowsAfterDisplayRow(row)" :key="crow.key">
            <div class="op-inline-content">
              <div class="op-inline-body" :class="{ 'is-streaming-md': crow.streaming }">
                <StaticMarkdown :source="crow.text" :defer-mermaid="crow.streaming" :streaming="crow.streaming" />
              </div>
            </div>
          </template>
        </template>
        <template v-else>
        <template v-for="step in [row.step]" :key="`${step.id}-${hitlRevision}-${step.summary?.active ?? ''}`">
        <PlanDagPanel
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
          :data-live-taskboard="isLiveTaskboardAnchor(step) ? '1' : undefined"
          :step="step"
          :live="isLiveTaskboardAnchor(step)"
        />
        <SubagentCard
          v-else-if="isSubagentStep(step)"
          :step="step"
          :live="live && lifecycleOf(step) === 'running'"
        />
        <WorkerCard
          v-else-if="isWorkerStep(step)"
          :step="step"
          :live="live && lifecycleOf(step) === 'running'"
        />
        <DecisionCard
          v-else-if="isDecisionStep(step)"
          :step="step"
          :live="live && lifecycleOf(step) === 'awaiting'"
          :generation-id="generationId"
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
          <!-- 展开态：worker/sub-agent 的嵌套时间线（subSteps） -->
          <div
            v-if="isCardExpanded(step) && hasNestedBodyTimeline(step)"
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
        <!-- Plan DAG 下 node-answer 正文锚定到 plan，须在 PlanDagPanel 之后渲染 -->
        <template v-for="crow in rowsAfterStep(step.id)" :key="crow.key">
          <div class="op-inline-content">
            <div class="op-inline-body" :class="{ 'is-streaming-md': crow.streaming }">
              <StaticMarkdown :source="crow.text" :defer-mermaid="crow.streaming" :streaming="crow.streaming" />
            </div>
          </div>
        </template>
        </template>
        </template>
        </div>
        </template>      </template>
      <template v-for="row in orphanContent" :key="row.key">
        <div class="op-inline-content">
          <div class="op-inline-body" :class="{ 'is-streaming-md': row.streaming }">
            <StaticMarkdown :source="row.text" :defer-mermaid="row.streaming" :streaming="row.streaming" />
          </div>
        </div>
    </template>
    <!-- 执行空档：三点跳动（流式静默 ≥2s） -->
    <div
      v-if="showExpandedPendingDots"
      class="op-answer-dots"
      aria-label="正在执行"
    >
      <span class="typing-dots">
        <span class="dot" /><span class="dot" /><span class="dot" />
      </span>
    </div>
  </template>
    <!-- 折叠态常驻 taskboard：生成 todolist 后折叠时间线仍可见（进行中/终态均露出） -->
    <!-- 折叠态常驻 taskboard：生成 todolist 后折叠时间线仍可见（进行中/终态均露出） -->
    <TaskBoardPanel
      v-if="!timelineBodyExpanded && collapsedTaskBoardStep"
      :data-live-taskboard="isLiveTaskboardAnchor(collapsedTaskBoardStep) ? '1' : undefined"
      :step="collapsedTaskBoardStep"
      :live="isLiveTaskboardAnchor(collapsedTaskBoardStep)"
    />
    <!-- 正在处理折叠：最后一步概要（无 chevron）+ 最后一段正文 -->
    <template v-if="!timelineBodyExpanded && collapsedPreviewStep">
      <PlanDagPanel
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
      <WorkerCard
        v-else-if="isWorkerStep(collapsedPreviewStep)"
        :step="collapsedPreviewStep"
        :live="live && lifecycleOf(collapsedPreviewStep) === 'running'"
      />
      <DecisionCard
        v-else-if="isDecisionStep(collapsedPreviewStep)"
        :step="collapsedPreviewStep"
        :live="live && lifecycleOf(collapsedPreviewStep) === 'awaiting'"
        :generation-id="generationId"
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
            :streaming="!!(streamLive || live)"
          />
        </div>
      </div>
      <!-- 折叠态执行空档：三点跳动（流式静默 ≥2s） -->
      <div
        v-if="showCollapsedPendingDots"
        class="op-answer-dots"
        aria-label="正在执行"
      >
        <span class="typing-dots">
          <span class="dot" /><span class="dot" /><span class="dot" />
        </span>
      </div>
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
  content-visibility: auto;
  contain-intrinsic-size: auto 40px;
  opacity: 0.9;
  transition: opacity 0.15s;
}

.op-row:hover {
  opacity: 1;
}

/* 主时间线行间距统一 margin-top 单侧 8px（flex 中 margin 不折叠，避免 gap+margin 叠加）；
   折叠组内行间距由各 body 的 gap/margin-top 承担；HITL 紧跟其操作行 */
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

/* 文字后展开箭头：紧跟概要文字，hover / 展开态显示 */
.timeline-summary .op-chevron {
  flex-shrink: 0;
  align-self: center;
  width: 12px;
  height: 12px;
  color: var(--sun-text-secondary);
  opacity: 0;
  margin-left: 2px;
  transition: transform 0.15s ease, opacity 0.12s ease;
  transform: rotate(0deg);
}

.timeline-summary:not(.is-expanded):hover .op-chevron {
  opacity: 0.85;
}

.timeline-summary.is-expanded .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
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
  --op-shimmer-base: var(--sun-shimmer-base);
  --op-shimmer-peak: var(--sun-shimmer-peak);
  display: inline-block;
  max-width: 100%;
  background-image: linear-gradient(
    90deg,
    var(--op-shimmer-base) 0%,
    var(--op-shimmer-base) 38%,
    var(--op-shimmer-peak) 50%,
    var(--op-shimmer-base) 62%,
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
  --op-shimmer-base: var(--sun-shimmer-label-base);
  --op-shimmer-peak: var(--sun-shimmer-label-peak);
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
  opacity: 1;
  /* 阶段正文：与 card 间距 8px；下方间距由下一行的 margin-top 承担，避免叠加 */
  margin: 8px 0 0;
}

/* 内部时间线不缩进（与工具折叠一致平铺）；仅保留与主行的间距 */
.op-nested-stack {
  margin: 2px 0 8px 0;
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

.op-answer-dots {
  margin-top: 8px;
  display: flex;
  align-items: center;
  min-height: 1.5em;
}

/* 完成 ✓：紧跟文案 */
.op-check {
  color: var(--sun-text-muted);
  display: inline-flex;
  align-items: center;
  align-self: center;
  flex-shrink: 0;
}

/* 正文间多轮折叠 */
.round-group {
  min-width: 0;
}

.round-group-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
  width: 100%;
  padding: 1px 0;
  border: none;
  background: transparent;
  color: inherit;
  font-size: var(--sun-font-md, 14px);
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
}

.round-group-label {
  color: var(--sun-text-muted);
  font-weight: 450;
}

.round-group-label.op-shimmer {
  --op-shimmer-base: var(--sun-shimmer-label-base);
  --op-shimmer-peak: var(--sun-shimmer-label-peak);
}

.round-group .op-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.round-group .op-main .op-chevron {
  flex-shrink: 0;
  align-self: center;
  width: 12px;
  height: 12px;
  color: var(--sun-text-secondary);
  opacity: 0;
  margin-left: 2px;
  transition: transform 0.15s ease, opacity 0.12s ease;
  transform: rotate(0deg);
}

.round-group:not(.is-expanded):hover .op-main .op-chevron {
  opacity: 0.85;
}

.round-group.is-expanded .op-main .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}

.round-group-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* 折叠头与展开区首行保持 8px（gap 只作用于 body 内部行之间） */
  margin-top: 8px;
}

.round-group-body .op-line {
  contain: layout style;
}

.round-group-body .op-line.is-clickable {
  cursor: pointer;
}

.op-line-hitl :deep(.collapsible-confirm) {
  margin-left: 0;
}

/* 行首图标槽位：固定 16px，type-icon 与 chevron 绝对定位重叠；仅标准模式渲染（根 class 限定） */
.is-timeline-standard .op-step-icon {
  position: relative;
  flex-shrink: 0;
  align-self: center;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.is-timeline-standard .op-step-icon .op-type-icon,
.is-timeline-standard .op-step-icon .op-chevron {
  position: absolute;
  transition: opacity 0.12s ease, transform 0.15s ease;
}

.is-timeline-standard .op-step-icon .op-type-icon {
  opacity: 1;
}

.is-timeline-standard .op-step-icon .op-chevron {
  color: var(--sun-text-secondary);
  opacity: 0;
  margin: 0;
}

.is-timeline-standard .timeline-summary.is-clickable:hover .op-step-icon .op-type-icon,
.is-timeline-standard .round-group:not(.is-expanded):hover .op-step-icon .op-type-icon {
  opacity: 0;
}

.is-timeline-standard .timeline-summary.is-clickable:hover .op-step-icon .op-chevron,
.is-timeline-standard .round-group:not(.is-expanded):hover .op-step-icon .op-chevron {
  opacity: 0.85;
}

.is-timeline-standard .timeline-summary.is-expanded .op-step-icon .op-type-icon,
.is-timeline-standard .round-group.is-expanded .op-step-icon .op-type-icon {
  opacity: 0;
}

.is-timeline-standard .timeline-summary.is-expanded .op-step-icon .op-chevron,
.is-timeline-standard .round-group.is-expanded .op-step-icon .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}
</style>
