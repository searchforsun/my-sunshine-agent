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
  resolveStepExpandBody,
  parseLoadedSkillLabel,
  stripLoadedSkillPrefix,
  shouldShiftSummaryOnExpand,
  hasExpandableContent,
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

  /** @deprecated V1 兼容，展示以 summary 为准 */

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
  const terminal = lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped'
  return {
    before: incoming.before ?? prev.before,
    active: terminal ? undefined : (incoming.active ?? prev.active),
    after: incoming.after ?? prev.after,
  }
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
      byId.set(step.id, {
        ...existing,
        ...step,
        summary: mergeSummary(existing.summary, step.summary, step.lifecycle ?? existing.lifecycle),
        reasoning: longerText(existing.reasoning, step.reasoning),
        output: longerText(existing.output, step.output),
        result: step.result ?? existing.result,
        detail: step.detail ?? existing.detail,
        metadata: mergeStepMetadata(existing.metadata, step.metadata, step.lifecycle ?? existing.lifecycle),
        lifecycle: step.lifecycle ?? existing.lifecycle,
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

    const lifecycle = incoming.lifecycle ?? prev.lifecycle

    const merged: ProcessingStep = {

      ...prev,

      ...incoming,

      summary: mergeSummary(prev.summary, incoming.summary, lifecycle),

      reasoning: longerText(prev.reasoning, incoming.reasoning),

      output: longerText(prev.output, incoming.output),

      result: longerText(prev.result, incoming.result),

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

    phase: delta.stepId as StepPhase,

    lifecycle: 'running',

    label: delta.stepId,

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

export function hasActiveStep(steps: ProcessingStep[] | undefined): boolean {

  return !!steps?.some(s => s.lifecycle === 'running')

}

export {
  shouldIgnoreResumeStepReplay,
  pauseRunningWorkflowNodes,
  reactivatePausedStepsForResume,
  reactivateOtherPausedWorkflowNodes,
} from './processingStepsPause'

