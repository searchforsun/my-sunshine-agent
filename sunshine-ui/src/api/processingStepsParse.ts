/** SSE / REST steps JSON 解析 */
import type { PlanApprovalRoundView } from './planApprovalSteps'
import type { PlanGraph } from './executionPlans'
import type { ContentBlock } from './contentInterleave'
import type {
  ProcessingStep,
  StepLifecycle,
  StepMetadata,
  StepPhase,
  StepSummary,
} from './processingSteps'

function parseSummary(raw: unknown): StepSummary | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const obj = raw as Record<string, unknown>
  const before = typeof obj.before === 'string' ? obj.before : undefined
  const active = typeof obj.active === 'string' ? obj.active : undefined
  const after = typeof obj.after === 'string' ? obj.after : undefined
  if (!before && !active && !after) return undefined
  return { before, active, after }
}

function parseNodeAttempts(raw: unknown): StepMetadata['nodeAttempts'] {
  if (!Array.isArray(raw) || raw.length === 0) return undefined
  const attempts = raw
    .map(item => {
      if (!item || typeof item !== 'object') return null
      const o = item as Record<string, unknown>
      const attemptNo = typeof o.attemptNo === 'number' ? o.attemptNo : undefined
      const status = typeof o.status === 'string' ? o.status : undefined
      if (attemptNo == null || !status) return null
      return {
        attemptNo,
        status,
        errorClass: typeof o.errorClass === 'string' ? o.errorClass : undefined,
        summary: typeof o.summary === 'string' ? o.summary : undefined,
        startedAt: typeof o.startedAt === 'number' ? o.startedAt : undefined,
        endedAt: typeof o.endedAt === 'number' ? o.endedAt : undefined,
      }
    })
    .filter((a): a is NonNullable<typeof a> => !!a)
  return attempts.length > 0 ? attempts : undefined
}

function parseMetadata(raw: unknown): StepMetadata | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const obj = raw as Record<string, unknown>
  const hitCount = typeof obj.hitCount === 'number' ? obj.hitCount : undefined
  const sources = Array.isArray(obj.sources)
    ? obj.sources.filter((s): s is string => typeof s === 'string' && s.trim().length > 0)
    : undefined
  const rewriteApplied = obj.rewriteApplied === true ? true : undefined
  const rewriteLatencyMs = typeof obj.rewriteLatencyMs === 'number' ? obj.rewriteLatencyMs : undefined
  const rewriteFrom = typeof obj.rewriteFrom === 'string' && obj.rewriteFrom.trim()
    ? obj.rewriteFrom.trim()
    : undefined
  const rewriteTo = typeof obj.rewriteTo === 'string' && obj.rewriteTo.trim()
    ? obj.rewriteTo.trim()
    : undefined
  const rewriteScenario = typeof obj.rewriteScenario === 'string' && obj.rewriteScenario.trim()
    ? obj.rewriteScenario.trim()
    : undefined
  const rewriteScenarioLabel = typeof obj.rewriteScenarioLabel === 'string' && obj.rewriteScenarioLabel.trim()
    ? obj.rewriteScenarioLabel.trim()
    : undefined
  const skillId = typeof obj.skillId === 'string' && obj.skillId.trim()
    ? obj.skillId.trim()
    : undefined
  const plannerMode = typeof obj.plannerMode === 'string' && obj.plannerMode.trim()
    ? obj.plannerMode.trim()
    : undefined
  const routingReason = typeof obj.routingReason === 'string' && obj.routingReason.trim()
    ? obj.routingReason.trim()
    : undefined
  const rewriteInDetail = obj.rewriteInDetail === true ? true : undefined
  const expandSectionTitle = typeof obj.expandSectionTitle === 'string' && obj.expandSectionTitle.trim()
    ? obj.expandSectionTitle.trim()
    : undefined
  const hitlRaw = obj.hitl && typeof obj.hitl === 'object'
    ? obj.hitl as Record<string, unknown>
    : null
  const hitlStatus = typeof hitlRaw?.status === 'string'
    ? hitlRaw.status as StepMetadata['hitlStatus']
    : undefined
  const hitlToken = typeof hitlRaw?.token === 'string' && hitlRaw.token.trim()
    ? hitlRaw.token.trim()
    : undefined
  const hitlToolDisplayName = typeof hitlRaw?.toolDisplayName === 'string'
    ? hitlRaw.toolDisplayName
    : undefined
  const hitlParamsSummary = typeof hitlRaw?.paramsSummary === 'string'
    ? hitlRaw.paramsSummary
    : undefined
  const hitlExpiresAt = typeof hitlRaw?.expiresAt === 'number' ? hitlRaw.expiresAt : undefined
  const recoveryRaw = obj.recovery && typeof obj.recovery === 'object'
    ? obj.recovery as Record<string, unknown>
    : null
  const recoveryStatus = typeof recoveryRaw?.status === 'string'
    ? recoveryRaw.status as StepMetadata['recoveryStatus']
    : undefined
  const recoveryToken = typeof recoveryRaw?.token === 'string' && recoveryRaw.token.trim()
    ? recoveryRaw.token.trim()
    : undefined
  const recoveryError = typeof recoveryRaw?.errorMessage === 'string'
    ? recoveryRaw.errorMessage
    : undefined
  const recoveryExpiresAt = typeof recoveryRaw?.expiresAt === 'number' ? recoveryRaw.expiresAt : undefined
  const nodeAttempts = parseNodeAttempts(obj.nodeAttempts)
  const planApprovalRaw = obj.planApproval && typeof obj.planApproval === 'object'
    ? obj.planApproval as Record<string, unknown>
    : null
  const planApprovalStatus = typeof planApprovalRaw?.status === 'string'
    ? planApprovalRaw.status as StepMetadata['planApproval'] extends { status?: infer S } ? S : never
    : undefined
  const planApprovalToken = typeof planApprovalRaw?.token === 'string' && planApprovalRaw.token.trim()
    ? planApprovalRaw.token.trim()
    : undefined
  const planApprovalExpiresAt = typeof planApprovalRaw?.expiresAt === 'number'
    ? planApprovalRaw.expiresAt
    : undefined
  const planApprovalRounds = Array.isArray(planApprovalRaw?.rounds)
    ? planApprovalRaw.rounds
        .filter((r): r is Record<string, unknown> => r && typeof r === 'object')
        .map((r) => ({
          roundNo: typeof r.roundNo === 'number' ? r.roundNo : 0,
          status: (typeof r.status === 'string' ? r.status : 'awaiting') as PlanApprovalRoundView['status'],
          userHint: typeof r.userHint === 'string' ? r.userHint : undefined,
          chainSummary: typeof r.chainSummary === 'string' ? r.chainSummary : undefined,
          createdAt: typeof r.createdAt === 'number' ? r.createdAt : undefined,
          resolvedAt: typeof r.resolvedAt === 'number' ? r.resolvedAt : undefined,
        }))
    : undefined
  const planApprovalGraphRaw = planApprovalRaw?.planGraph
  const planApprovalPlanGraph = planApprovalGraphRaw && typeof planApprovalGraphRaw === 'object'
    ? planApprovalGraphRaw as PlanGraph
    : undefined
  const planApproval = planApprovalRaw
    ? {
        status: planApprovalStatus,
        token: planApprovalToken,
        expiresAt: planApprovalExpiresAt,
        rounds: planApprovalRounds,
        planGraph: planApprovalPlanGraph,
      }
    : undefined
  if (
    hitCount == null
    && (!sources || sources.length === 0)
    && !rewriteApplied
    && !skillId
    && !plannerMode
    && !routingReason
    && !rewriteInDetail
    && !expandSectionTitle
    && !hitlStatus
    && !recoveryStatus
    && !nodeAttempts?.length
    && !planApproval
  ) {
    return undefined
  }
  return {
    hitCount,
    sources,
    rewriteApplied,
    rewriteLatencyMs,
    rewriteFrom,
    rewriteTo,
    rewriteScenario,
    rewriteScenarioLabel,
    skillId,
    plannerMode,
    routingReason,
    rewriteInDetail,
    expandSectionTitle,
    hitlStatus,
    hitlToken,
    hitlToolDisplayName,
    hitlParamsSummary,
    hitlExpiresAt,
    recoveryStatus,
    recoveryToken,
    recoveryError,
    recoveryExpiresAt,
    nodeAttempts,
    planApproval,
  }
}

/** upsert 时合并 metadata（含 HITL/Recovery/PlanApproval） */
export function mergeStepMetadata(
  prev?: StepMetadata,
  incoming?: StepMetadata,
  lifecycle?: StepLifecycle,
): StepMetadata | undefined {
  if (!prev && !incoming) return undefined
  if (!prev) return incoming
  if (!incoming) return prev
  const merged: StepMetadata = {
    ...prev,
    ...(Object.fromEntries(
      Object.entries(incoming).filter(([, v]) => v !== undefined),
    ) as Partial<StepMetadata>),
  }
  if (incoming.hitlStatus && incoming.hitlStatus !== 'awaiting') {
    merged.hitlToken = undefined
  }
  if (lifecycle === 'done' && merged.recoveryStatus === 'retry') {
    merged.recoveryStatus = undefined
    merged.recoveryToken = undefined
    merged.recoveryError = undefined
    merged.recoveryExpiresAt = undefined
  }
  const prevAttempts = prev.nodeAttempts?.length ?? 0
  const incomingAttempts = incoming.nodeAttempts?.length ?? 0
  if (incomingAttempts > prevAttempts) {
    merged.nodeAttempts = incoming.nodeAttempts
  }
  if (incoming.planApproval || prev.planApproval) {
    merged.planApproval = {
      ...prev.planApproval,
      ...incoming.planApproval,
      rounds: incoming.planApproval?.rounds?.length
        ? incoming.planApproval.rounds
        : prev.planApproval?.rounds,
      planGraph: incoming.planApproval?.planGraph?.nodes?.length
        ? incoming.planApproval.planGraph
        : prev.planApproval?.planGraph,
    }
  }
  return merged
}

export function parseContentBlocks(raw: unknown): ContentBlock[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined
  const blocks: ContentBlock[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const o = item as Record<string, unknown>
    const segmentId = typeof o.segmentId === 'string' ? o.segmentId : ''
    const afterStepId = typeof o.afterStepId === 'string' ? o.afterStepId : ''
    const text = typeof o.text === 'string' ? o.text : ''
    if (segmentId && afterStepId) blocks.push({ segmentId, afterStepId, text })
  }
  return blocks.length > 0 ? blocks : undefined
}

function parseSubSteps(raw: unknown): ProcessingStep[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined
  const steps = raw
    .map(item => (item && typeof item === 'object' ? normalizeStep(item as Record<string, unknown>) : null))
    .filter((s): s is ProcessingStep => !!s)
  return steps.length > 0 ? steps : undefined
}

export function normalizeStep(raw: Record<string, unknown>): ProcessingStep | null {
  if (typeof raw.id !== 'string') return null
  const id = raw.id
  const phase = (typeof raw.phase === 'string'
    ? raw.phase
    : (id.startsWith('node-') ? 'node' : 'generate')) as StepPhase
  const lifecycle = (
    typeof raw.lifecycle === 'string' ? raw.lifecycle : 'running'
  ) as StepLifecycle
  const label = typeof raw.label === 'string' ? raw.label : undefined
  const summary = parseSummary(raw.summary)
  if (!summary) return null
  return {
    id: raw.id,
    phase,
    lifecycle,
    summary,
    startedAt: typeof raw.startedAt === 'number' ? raw.startedAt : undefined,
    endedAt: typeof raw.endedAt === 'number' ? raw.endedAt : undefined,
    durationMs: typeof raw.durationMs === 'number' ? raw.durationMs : undefined,
    detail: typeof raw.detail === 'string' ? raw.detail : undefined,
    reasoning: typeof raw.reasoning === 'string' ? raw.reasoning : undefined,
    output: typeof raw.output === 'string' ? raw.output : undefined,
    result: typeof raw.result === 'string' ? raw.result : undefined,
    ts: typeof raw.ts === 'number' ? raw.ts : undefined,
    label,
    metadata: parseMetadata(raw.metadata),
    subSteps: parseSubSteps(raw.subSteps),
    contentBlocks: parseContentBlocks(raw.contentBlocks),
  }
}
