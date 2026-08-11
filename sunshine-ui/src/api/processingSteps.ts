/**

 * 后端处理流水线步骤 — SSE type:step (V2: lifecycle + summary + duration)

 */

import { relocateAgentNodeHitl } from './hitlSteps'
import type { PlanApprovalRoundView } from './planApprovalSteps'
import type { PlanGraph } from './executionPlans'
import type { ContentBlock } from './contentInterleave'
import type { SandboxEditDiffMeta } from './sandboxEditDiff'
import { mergeStepMetadata } from './processingStepsParse'
import { resolveStepDurationMs } from './processingStepsDisplay'
import { sortSteps, isWorkflowNodeStepId, isThinkStepId } from './processingStepsNormalize'

export { normalizeStep, parseContentBlocks } from './processingStepsParse'
export {
  sortSteps,
  isWorkflowNodeStepId,
  STEP_ORDER,
} from './processingStepsNormalize'
export type { RewriteDetailView, TimelineMessageStatus } from './processingStepsDisplay'
export {
  formatStepLabel,
  formatDuration,
  stepLifecycle,
  formatStepMetadata,
  formatRewriteLatency,
  formatRewriteMetadata,
  resolveRewriteDetail,
  STEP_HEADER_PREVIEW_MAX,
  resolveStepSummaryFull,
  resolveStepHeaderText,
  resolveStepExpandSummary,
  resolveStepExpandInner,
  resolveStepExpandBody,
  resolveStepExpandPanels,
  parseLoadedSkillLabel,
  stripLoadedSkillPrefix,
  shouldShiftSummaryOnExpand,
  hasExpandableContent,
  isStepSummaryTruncated,
  isSandboxToolStep,
  isSandboxExecStep,
  isSandboxReadStep,
  isCancellableSandboxTool,
  extractSandboxExecCommand,
  formatExecCommandHeader,
  formatExecCommandHeaderText,
  extractSandboxWorkspacePath,
  extractSandboxSearchRoot,
  inferSandboxSearchRoot,
  resolveSandboxFocusPath,
  resolveSandboxReadLineRange,
  parseSandboxPathList,
  isSandboxPathListOutput,
  sandboxBasename,
  resolveStepDurationMs,
  totalDuration,
  summarizeSteps,
  isWorkflowAnswerStep,
  formatElapsedClock,
  resolveTimelineElapsedMs,
  resolveTimelineSummaryPrefix,
  formatTimelineSummaryText,
} from './processingStepsDisplay'

export {
  parsePlanStepMeta,
  resolvePlanStepDetail,
  resolvePlanIdFromStep,
} from './processingStepsPlan'
export type { PlanStepDetailView } from './processingStepsPlan'

/** ReAct TaskBoard 清单项（SSE metadata.tasks） */
export interface TaskBoardItemView {
  id: string
  content: string
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled'
}

/** 已收到 manage_tasks 落库后的真实清单（占位步无 revision/items） */
export function hasRealTaskBoardItems(step: ProcessingStep): boolean {
  const tasks = step.metadata?.tasks ?? []
  return tasks.length > 0 && (step.metadata?.taskRevision ?? 0) >= 1
}

/** ReAct spawn_subagent 主时间线卡片（phase 或 id 前缀） */
export function isSubagentStep(step: { id?: string; phase?: string }): boolean {
  return step.phase === 'subagent' || !!step.id?.startsWith('subagent-')
}

/** ReAct request_decision 主时间线卡片（phase 或 id 前缀） */
export function isDecisionStep(step: { id?: string; phase?: string }): boolean {
  return step.phase === 'decision' || !!step.id?.startsWith('decision-')
}

export interface DecisionOptionView {
  id: string
  label: string
}

export interface DecisionQuestionView {
  id: string
  prompt: string
  options: DecisionOptionView[]
  allowMultiple?: boolean
}

export interface DecisionAnswerView {
  questionId: string
  selectedOptionIds: string[]
  customInput?: string
}

export interface DecisionMeta {
  token?: string
  title?: string
  questions?: DecisionQuestionView[]
  expiresAt?: number
  outcome?: string
  answers?: DecisionAnswerView[]
}

export type StepPhase = 'intent' | 'rag' | 'agent' | 'think' | 'generate' | string

export type StepStatus = 'pending' | 'running' | 'awaiting' | 'done' | 'error' | 'skipped' | 'paused' | 'terminated'

export type StepLifecycle = StepStatus



export interface StepSummary {

  before?: string

  active?: string

  after?: string

}

/** RAG / QueryRewrite 等步骤的结构化元数据（后端 SSE 下发） */
export interface StepMetadata {
  hitCount?: number
  sources?: string[]
  rewriteApplied?: boolean
  rewriteLatencyMs?: number
  rewriteFrom?: string
  rewriteTo?: string
  rewriteScenario?: string
  /** 改写场景时机说明（后端 SSE / metadata 下发，勿在前端硬编码） */
  rewriteScenarioLabel?: string
  skillId?: string
  plannerMode?: string
  routingReason?: string
  /** 改写链路已在 detail，勿再渲染结构化改写区 */
  rewriteInDetail?: boolean
  /** 展开区 detail 区块标题 */
  expandSectionTitle?: string
  /** 写工具 HITL（step.metadata.hitl 扁平字段） */
  hitlStatus?: 'awaiting' | 'approved' | 'denied'
  hitlToken?: string
  hitlToolDisplayName?: string
  hitlParamsSummary?: string
  hitlExpiresAt?: number
  /** Workflow 节点失败：用户重试/终止 */
  recoveryStatus?: 'awaiting' | 'retry' | 'skipped' | 'terminated'
  recoveryToken?: string
  recoveryError?: string
  recoveryExpiresAt?: number
  /** Workflow 节点执行 attempt（重试过程 SSE 实时下发） */
  nodeAttempts?: import('./executionPlans').PlanNodeAttempt[]
  /** 动态 Plan 用户确认 */
  planApproval?: {
    status?: 'awaiting' | 'approved'
    token?: string
    expiresAt?: number
    rounds?: PlanApprovalRoundView[]
    planGraph?: PlanGraph
  }
  /** ReAct TaskBoard */
  tasks?: TaskBoardItemView[]
  taskRevision?: number
  taskProgress?: string
  /** 沙箱 read/write/edit 完整容器路径 */
  sandboxPath?: string
  /** 沙箱 glob 搜索根 */
  sandboxSearchRoot?: string
  /** ReAct spawn_subagent：传入子 Agent 的 prompt */
  spawnPrompt?: string
  /** 沙箱可单工具取消（后端 Nacos cancellable-tools） */
  cancellable?: boolean
  /** 沙箱 edit：Git contextual diff（绝对行号）；UI 只认此字段 */
  editDiff?: SandboxEditDiffMeta
  /** ReAct request_decision（SSE metadata.decision，勿截断 title/questions） */
  decision?: DecisionMeta
}



export interface ProcessingStep {

  id: string

  phase: StepPhase

  lifecycle: StepLifecycle

  summary?: StepSummary

  startedAt?: number

  endedAt?: number

  durationMs?: number

  /** 前端墙钟：收到 running 步时记本地时刻，避免与服务端 startedAt 时钟差导致 live 计时偏移 */
  clientStartedAt?: number

  detail?: string

  /** V3：步骤内流式思考 */
  reasoning?: string

  /** V3：步骤内输出/日志 */
  output?: string

  /** V3：步骤结果摘要 */
  result?: string

  /** think 步本轮摘要（由 think_summary 元工具结构化输出，经 step_summary 通道下发） */
  stepSummary?: string

  ts?: number

  /** 步骤展示名（wire SSOT）；主行摘要以 summary 为准 */
  label?: string

  metadata?: StepMetadata

  /** Workflow agent 节点：子 Agent 完整 ReAct 步骤（抽屉内展示） */
  subSteps?: ProcessingStep[]

  /** 子 Agent 正文分段（抽屉 OperationStack 穿插于 subSteps） */
  contentBlocks?: ContentBlock[]

}



function mergeSummary(
  prev?: StepSummary,
  incoming?: StepSummary,
  lifecycle?: StepLifecycle,
): StepSummary | undefined {
  if (!prev && !incoming) return undefined
  if (!prev) return incoming
  if (!incoming) return prev
  const terminal = isHardTerminalLifecycle(lifecycle)
    || (lifecycle === 'paused' && !!(incoming.after ?? prev.after))
  return {
    before: incoming.before ?? prev.before,
    active: terminal ? undefined : (incoming.active ?? prev.active),
    after: incoming.after ?? prev.after,
  }
}

/** done/error/skipped/terminated；paused+after 为用户取消终态（HITL 中途 paused 无 after 可续跑） */
function isHardTerminalLifecycle(lifecycle?: StepLifecycle): boolean {
  return lifecycle === 'done'
    || lifecycle === 'error'
    || lifecycle === 'skipped'
    || lifecycle === 'terminated'
}

function isCancelTerminalStep(step: ProcessingStep): boolean {
  return step.lifecycle === 'paused' && !!step.summary?.after?.trim()
}

function resolveMergedLifecycle(
  prev: ProcessingStep,
  incoming: ProcessingStep,
): StepLifecycle {
  const next = (incoming.lifecycle ?? prev.lifecycle) as StepLifecycle
  // think 步例外：ReAct 最后一轮 reasoning 输出 todo_write 后被 endReasoningRound 置 done，
  // 继续第二轮 reasoning 时 beginReasoningRound 走复用分支发 resume（running）——后端有意复用
  // 同一 think 卡片续写（TimelineSessionThinkFlow.beginReasoningRound）。此处放行 done→running，
  // 让计时器/摘要随复用连续，否则硬终态保护会冻结在首个 done（如 9.2s）直到终态跳变。
  if (next === 'running' && isThinkStepId(incoming.id)) {
    return 'running'
  }
  if (
    (isHardTerminalLifecycle(prev.lifecycle) || isCancelTerminalStep(prev))
    && (next === 'running' || next === 'pending')
  ) {
    return prev.lifecycle as StepLifecycle
  }
  return next
}



function mergeSubSteps(
  prev?: ProcessingStep[],
  incoming?: ProcessingStep[],
): ProcessingStep[] | undefined {
  if (!incoming?.length) return prev
  if (!prev?.length) return incoming
  const byId = new Map(prev.map(s => [s.id, s]))
  for (const step of incoming) {
    const existing = byId.get(step.id)
    if (existing) {
      const lifecycle = step.lifecycle ?? existing.lifecycle
      byId.set(step.id, {
        ...existing,
        ...step,
        summary: mergeSummary(existing.summary, step.summary, lifecycle),
        reasoning: longerText(existing.reasoning, step.reasoning),
        output: longerText(existing.output, step.output),
        result: longerText(existing.result, step.result),
        detail: step.detail ?? existing.detail,
        metadata: mergeStepMetadata(existing.metadata, step.metadata, lifecycle),
        lifecycle,
        // loop 内 agent：递归保留 think/tool 与流式 contentBlocks
        subSteps: mergeSubSteps(existing.subSteps, step.subSteps),
        contentBlocks: step.contentBlocks?.length ? step.contentBlocks : existing.contentBlocks,
      })
    } else {
      byId.set(step.id, step)
    }
  }
  return sortSteps([...byId.values()])
}

export function upsertStep(steps: ProcessingStep[], incoming: ProcessingStep): ProcessingStep[] {

  const idx = steps.findIndex(s => s.id === incoming.id)

  const next = [...steps]

  if (idx >= 0) {

    const prev = next[idx]

    const lifecycle = resolveMergedLifecycle(prev, incoming)

    // 后端 step 事件从不携带增量 reasoning（aggregator 不落 reasoning，reasoning 仅经 step_delta 下发），
    // 故 running 快照 incoming.reasoning 恒为 null，无法据此区分「中断续传」与「同 id 复用」。
    // 中断恢复的清理由 resetStepsForReactResume（resume 时重置 pending + 清 reasoning）保证；
    // 此处统一 longerText：复用（如建板后再推理）prev 非空、incoming null → 保留 prev，
    // 后续 step_delta 经 concatText 续写 → 累加，不覆盖。
    const merged: ProcessingStep = {

      ...prev,

      ...incoming,

      summary: mergeSummary(prev.summary, incoming.summary, lifecycle),

      reasoning: longerText(prev.reasoning, incoming.reasoning),

      output: longerText(prev.output, incoming.output),

      // done/error 终稿覆盖流式累积（对齐后端 ProcessingStepMerger.mergeResult）
      result: mergeStepResult(prev.result, incoming.result, lifecycle),

      detail: incoming.detail ?? prev.detail,

      // stepSummary 由 think_summary 经 step_delta(step_summary) 写入，后端 think 步
      // complete 快照同样携带；incoming 缺失（历史/早期版本）时保留 prev，避免覆盖成兜底。
      stepSummary: incoming.stepSummary ?? prev.stepSummary,

      metadata: mergeStepMetadata(prev.metadata, incoming.metadata, lifecycle),

      subSteps: mergeSubSteps(prev.subSteps, incoming.subSteps),

      contentBlocks: incoming.contentBlocks?.length ? incoming.contentBlocks : prev.contentBlocks,

    durationMs: lifecycle === 'running' && isThinkStepId(incoming.id)
      ? undefined
      : (incoming.durationMs ?? prev.durationMs),

    // think 复用（done→running）：清 endedAt，避免 resolveStepDurationMs 用旧 endedAt-startedAt
    // 冻结在首个 done 的时长（如 9.2s），导致计时器不连续
    startedAt: incoming.startedAt ?? prev.startedAt,

    endedAt: lifecycle === 'running' && isThinkStepId(incoming.id)
      ? undefined
      : (incoming.endedAt ?? prev.endedAt),

    lifecycle,

    }

    // think 复用后无 endedAt，resolveStepDurationMs 返回 undefined → 回退 incoming.durationMs
    // （running 快照无 durationMs）→ prev.durationMs（旧 9.2s）残留；此处显式清空，
    // 让 live 计时走 clientStartedAt 而非残留 durationMs
    if (lifecycle === 'running' && isThinkStepId(merged.id)) {
      merged.durationMs = undefined
    }

    merged.durationMs = resolveStepDurationMs(merged) ?? merged.durationMs

    // live 计时用客户端墙钟：首次 running 记本地时刻；离开 running（done/paused/error）清空，
    // 避免与服务端 startedAt 时钟差导致 live 计时偏移（完成后回归服务端 durationMs）。
    // think 步例外：done 时保留 clientStartedAt（可能随后 resume 复用），running 时仅在无锚点
    // 时才记新时刻——复用场景锚点延续，计时器从 think 最初起点连续递增，不从 0 重计。
    const isThinkResume = isThinkStepId(incoming.id)
    if (lifecycle === 'running' && merged.clientStartedAt == null) {
      merged.clientStartedAt = Date.now()
    } else if (lifecycle !== 'running' && !isThinkResume) {
      merged.clientStartedAt = undefined
    }

    next[idx] = merged.id.startsWith('node-') ? relocateAgentNodeHitl(merged) : merged

  } else {

    // 新步直接 running 且无 clientStartedAt（applyStepDelta 的 base 已从 steps[idx] 拷贝，
    // 不会进此分支；仅真正新步打时间戳）
    if (incoming.lifecycle === 'running' && incoming.clientStartedAt == null) {
      next.push({ ...incoming, clientStartedAt: Date.now() })
    } else {
      next.push(incoming)
    }

  }

  return sortSteps(next)

}



export interface StepDelta {

  stepId: string

  channel: string

  text: string

}



export function applyStepDelta(steps: ProcessingStep[], delta: StepDelta): ProcessingStep[] {
  const idx = steps.findIndex(s => s.id === delta.stepId)
  if (idx < 0 && isWorkflowNodeStepId(delta.stepId)) {
    return steps
  }
  // 已存在步骤：原地追加文本，复用数组引用，避免每 token filter+upsert+sort
  if (idx >= 0) {
    const base = steps[idx]
    applyDeltaChannel(base, delta)
    if (base.lifecycle == null) base.lifecycle = 'running'
    if (base.lifecycle === 'running') {
      if (base.startedAt == null) base.startedAt = Date.now()
      if (base.clientStartedAt == null) base.clientStartedAt = Date.now()
    }
    return steps
  }
  const base: ProcessingStep = {
    id: delta.stepId,
    phase: delta.stepId as StepPhase,
    lifecycle: 'running',
    summary: { active: delta.stepId },
  }
  applyDeltaChannel(base, delta)
  if (base.lifecycle == null) base.lifecycle = 'running'
  if (base.lifecycle === 'running') {
    if (base.startedAt == null) {
      base.startedAt = Date.now()
    }
    // live 计时锚点与 upsertStep 对齐：running 步首见即记 clientStartedAt，之后 delta 不再重置，
    // 避免 think 的 step_delta(reasoning) 流与 step 事件交错时计时器反复归零跳变
    if (base.clientStartedAt == null) {
      base.clientStartedAt = Date.now()
    }
  }
  return upsertStep(steps, base)
}

function applyDeltaChannel(base: ProcessingStep, delta: StepDelta): void {
  switch (delta.channel) {
    case 'reasoning':
      base.reasoning = concatText(base.reasoning, delta.text)
      break
    case 'step_summary':
      base.stepSummary = delta.text
      break
    case 'output':
      base.output = concatText(base.output, delta.text)
      break
    case 'result':
      base.result = concatText(base.result, delta.text)
      break
    default:
      base.output = concatText(base.output, delta.text)
  }
}




function concatText(existing: string | undefined, chunk: string): string {
  if (!chunk) return existing ?? ''
  if (!existing) return chunk
  return existing + chunk
}



const REASONING_STEP_PRIORITY = ['agent', 'think', 'generate', 'rag', 'intent'] as const

export function findRunningStepId(steps: ProcessingStep[]): string | undefined {

  for (const id of REASONING_STEP_PRIORITY) {

    const step = steps.find(s => s.id === id)

    if (step && step.lifecycle === 'running') {

      return id

    }

  }

  const runningThink = steps.find(s => isThinkStepId(s.id)
    && s.lifecycle === 'running')

  if (runningThink) return runningThink.id

  return steps.find(s => s.lifecycle === 'running')?.id

}

function longerText(a?: string, b?: string): string | undefined {

  if (!a) return b

  if (!b) return a

  if (b.length >= a.length && b.startsWith(a)) return b

  if (a.length >= b.length && a.startsWith(b)) return a

  return a + b

}

/** 终态 result 全量覆盖；运行中仍用前缀合并 */
function mergeStepResult(
  prev?: string,
  incoming?: string,
  lifecycle?: StepLifecycle,
): string | undefined {
  if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'paused') {
    if (incoming != null && incoming !== '') return incoming
  }
  return longerText(prev, incoming)
}

export function hasActiveStep(steps: ProcessingStep[] | undefined): boolean {

  return !!steps?.some(s => s.lifecycle === 'running')

}

export {
  shouldIgnoreResumeStepReplay,
  pauseRunningWorkflowNodes,
  reactivatePausedStepsForResume,
  reactivateOtherPausedWorkflowNodes,
} from './processingStepsPause'

