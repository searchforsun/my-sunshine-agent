/**

 * 后端处理流水线步骤 — SSE type:step (V2: lifecycle + summary + duration)

 */

import { relocateAgentNodeHitl } from './hitlSteps'
import type { PlanApprovalRoundView } from './planApprovalSteps'
import type { PlanGraph } from './executionPlans'
import type { ContentBlock } from './contentInterleave'
import { mergeStepMetadata } from './processingStepsParse'
import { resolveStepDurationMs } from './processingStepsDisplay'
import { sortSteps, isWorkflowNodeStepId, isThinkStepId } from './processingStepsNormalize'

export { normalizeStep, parseContentBlocks } from './processingStepsParse'
export {
  sortSteps,
  isWorkflowNodeStepId,
  STEP_ORDER,
} from './processingStepsNormalize'
export type { RewriteDetailView } from './processingStepsDisplay'
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
  isCancellableSandboxTool,
  extractSandboxExecCommand,
  extractSandboxWorkspacePath,
  extractSandboxSearchRoot,
  inferSandboxSearchRoot,
  resolveSandboxFocusPath,
  parseSandboxPathList,
  isSandboxPathListOutput,
  sandboxBasename,
  resolveStepDurationMs,
  totalDuration,
  summarizeSteps,
  isWorkflowAnswerStep,
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

export type StepPhase = 'intent' | 'rag' | 'agent' | 'think' | 'generate' | string

export type StepStatus = 'pending' | 'running' | 'done' | 'error' | 'skipped' | 'paused' | 'terminated'

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
}



export interface ProcessingStep {

  id: string

  phase: StepPhase

  lifecycle: StepLifecycle

  summary?: StepSummary

  startedAt?: number

  endedAt?: number

  durationMs?: number

  detail?: string

  /** V3：步骤内流式思考 */
  reasoning?: string

  /** V3：步骤内输出/日志 */
  output?: string

  /** V3：步骤结果摘要 */
  result?: string

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

    const merged: ProcessingStep = {

      ...prev,

      ...incoming,

      summary: mergeSummary(prev.summary, incoming.summary, lifecycle),

      reasoning: longerText(prev.reasoning, incoming.reasoning),

      output: longerText(prev.output, incoming.output),

      // done/error 终稿覆盖流式累积（对齐后端 ProcessingStepMerger.mergeResult）
      result: mergeStepResult(prev.result, incoming.result, lifecycle),

      detail: incoming.detail ?? prev.detail,

      metadata: mergeStepMetadata(prev.metadata, incoming.metadata, lifecycle),

      subSteps: mergeSubSteps(prev.subSteps, incoming.subSteps),

      contentBlocks: incoming.contentBlocks?.length ? incoming.contentBlocks : prev.contentBlocks,

      durationMs: incoming.durationMs ?? prev.durationMs,

      startedAt: incoming.startedAt ?? prev.startedAt,

      endedAt: incoming.endedAt ?? prev.endedAt,

      lifecycle,

    }

    merged.durationMs = resolveStepDurationMs(merged) ?? merged.durationMs

    next[idx] = merged.id.startsWith('node-') ? relocateAgentNodeHitl(merged) : merged

  } else {

    next.push(incoming)

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

  const base: ProcessingStep = idx >= 0 ? { ...steps[idx] } : {
    id: delta.stepId,
    phase: delta.stepId.startsWith('expert-') ? 'expert' : (delta.stepId as StepPhase),
    lifecycle: 'running',
    summary: { active: delta.stepId },
  }

  switch (delta.channel) {

    case 'reasoning':

      base.reasoning = concatText(base.reasoning, delta.text)

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

  if (base.lifecycle == null) base.lifecycle = 'running'

  if (base.lifecycle === 'running' && base.startedAt == null) {

    base.startedAt = Date.now()

  }

  return upsertStep(steps.filter(s => s.id !== delta.stepId), base)

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

