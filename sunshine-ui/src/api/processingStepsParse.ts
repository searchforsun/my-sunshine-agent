/** SSE / REST steps JSON 解析 */
import type { SandboxEditDiffMeta, SandboxDiffLine } from './sandboxEditDiff'
import type { ContentBlock } from './contentInterleave'
import type {
  DecisionAnswerView,
  DecisionMeta,
  DecisionOptionView,
  DecisionQuestionView,
  ProcessingStep,
  StepLifecycle,
  StepMetadata,
  StepPhase,
  StepSummary,
  TaskBoardItemView,
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

function parseTaskBoardItems(raw: unknown): StepMetadata['tasks'] {
  if (!Array.isArray(raw) || raw.length === 0) return undefined
  const items = raw
    .map(item => parseTaskBoardItem(item, 0))
    .filter((item): item is NonNullable<typeof item> => !!item)
  return items.length > 0 ? items : undefined
}

/** depth 限制避免异常嵌套；二级不再解析 deeper secondary */
function parseTaskBoardItem(raw: unknown, depth: number): TaskBoardItemView | null {
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const id = typeof o.id === 'string' && o.id.trim()
    ? o.id.trim()
    : (typeof o.taskId === 'string' && o.taskId.trim() ? o.taskId.trim() : '')
  const content = typeof o.content === 'string' && o.content.trim()
    ? o.content.trim()
    : (typeof o.label === 'string' && o.label.trim() ? o.label.trim() : '')
  const status = normalizeTaskBoardStatus(typeof o.status === 'string' ? o.status.trim() : '')
  if (!id || !content || !status) return null
  const dependsOn = Array.isArray(o.dependsOn)
    ? o.dependsOn.filter((d): d is string => typeof d === 'string' && !!d.trim()).map(d => d.trim())
    : undefined
  let secondary: TaskBoardItemView[] | undefined
  if (depth < 1 && Array.isArray(o.secondary) && o.secondary.length > 0) {
    const nested = o.secondary
      .map(child => parseTaskBoardItem(child, depth + 1))
      .filter((child): child is TaskBoardItemView => !!child)
    if (nested.length > 0) secondary = nested
  }
  const view: TaskBoardItemView = { id, content, status }
  if (dependsOn && dependsOn.length > 0) view.dependsOn = dependsOn
  if (secondary) view.secondary = secondary
  return view
}

/** H1 TaskItem status → TaskBoard 状态；ReAct 四态原样保留 */
function normalizeTaskBoardStatus(
  status: string,
): TaskBoardItemView['status'] | null {
  if (status === 'pending' || status === 'in_progress' || status === 'completed' || status === 'cancelled') {
    return status
  }
  if (status === 'done') return 'completed'
  if (status === 'fail' || status === 'obsolete') return 'cancelled'
  return null
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

function parseEditDiff(raw: unknown): SandboxEditDiffMeta | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const obj = raw as Record<string, unknown>
  if (!Array.isArray(obj.lines) || obj.lines.length === 0) return undefined
  const lines = obj.lines
    .map(item => {
      if (!item || typeof item !== 'object') return null
      const o = item as Record<string, unknown>
      const kind = o.kind
      if (kind !== 'del' && kind !== 'add' && kind !== 'ctx' && kind !== 'fold') return null
      const text = typeof o.text === 'string' ? o.text : ''
      const oldLine = typeof o.oldLine === 'number'
        ? o.oldLine
        : o.oldLine === null ? null : undefined
      const newLine = typeof o.newLine === 'number'
        ? o.newLine
        : o.newLine === null ? null : undefined
      return { kind, text, oldLine, newLine } as SandboxDiffLine
    })
    .filter((line): line is SandboxDiffLine => line != null)
  if (lines.length === 0) return undefined
  const path = typeof obj.path === 'string' && obj.path.trim() ? obj.path.trim() : undefined
  const contextRadius = typeof obj.contextRadius === 'number' ? obj.contextRadius : undefined
  return { path, contextRadius, lines }
}

/** request_decision：仅认 questions[{id,prompt,options[{id,label}]}]，忽略旧扁平 question/options */
function parseDecisionOptions(raw: unknown): DecisionOptionView[] {
  if (!Array.isArray(raw) || raw.length === 0) return []
  const options: DecisionOptionView[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const o = item as Record<string, unknown>
    const id = typeof o.id === 'string' ? o.id.trim() : ''
    const label = typeof o.label === 'string' ? o.label : ''
    if (!id || !label) continue
    options.push({ id, label })
  }
  return options
}

function parseDecisionQuestions(raw: unknown): DecisionQuestionView[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined
  const questions: DecisionQuestionView[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const o = item as Record<string, unknown>
    const id = typeof o.id === 'string' ? o.id.trim() : ''
    const prompt = typeof o.prompt === 'string' ? o.prompt : ''
    if (!id || !prompt) continue
    const question: DecisionQuestionView = {
      id,
      prompt,
      options: parseDecisionOptions(o.options),
    }
    if (o.allowMultiple === true) question.allowMultiple = true
    questions.push(question)
  }
  return questions.length > 0 ? questions : undefined
}

function parseDecisionAnswers(raw: unknown): DecisionAnswerView[] | undefined {
  if (!Array.isArray(raw)) return undefined
  const answers: DecisionAnswerView[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const o = item as Record<string, unknown>
    const questionId = typeof o.questionId === 'string' ? o.questionId.trim() : ''
    if (!questionId) continue
    const selectedOptionIds = Array.isArray(o.selectedOptionIds)
      ? o.selectedOptionIds.filter((id): id is string => typeof id === 'string' && !!id.trim())
          .map(id => id.trim())
      : []
    const answer: DecisionAnswerView = { questionId, selectedOptionIds }
    if (typeof o.customInput === 'string' && o.customInput.trim()) {
      answer.customInput = o.customInput
    }
    answers.push(answer)
  }
  return answers
}

function parseDecision(raw: unknown): DecisionMeta | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const o = raw as Record<string, unknown>
  const token = typeof o.token === 'string' && o.token.trim() ? o.token.trim() : undefined
  const title = typeof o.title === 'string' ? o.title : undefined
  const questions = parseDecisionQuestions(o.questions)
  const expiresAt = typeof o.expiresAt === 'number' ? o.expiresAt : undefined
  const outcome = typeof o.outcome === 'string' && o.outcome.trim() ? o.outcome.trim() : undefined
  const answers = parseDecisionAnswers(o.answers)
  if (!token && title == null && !questions?.length && outcome == null && answers == null) {
    return undefined
  }
  return { token, title, questions, expiresAt, outcome, answers }
}

function parseRoutingTraces(raw: unknown): StepMetadata['routingTraces'] {
  if (!Array.isArray(raw) || raw.length === 0) return undefined
  const traces: StepMetadata['routingTraces'] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const o = item as Record<string, unknown>
    const layer = typeof o.layer === 'string' && o.layer.trim() ? o.layer.trim() : undefined
    const label = typeof o.label === 'string' && o.label.trim() ? o.label.trim() : undefined
    const detail = typeof o.detail === 'string' && o.detail.trim() ? o.detail.trim() : undefined
    if (!layer && !label && !detail) continue
    traces.push({ layer, label, detail })
  }
  return traces.length > 0 ? traces : undefined
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
  const tasks = parseTaskBoardItems(obj.tasks)
  const taskQueue = parseTaskBoardItems(obj.taskQueue)
  const taskRevision = typeof obj.taskRevision === 'number' ? obj.taskRevision : undefined
  const taskProgress = typeof obj.taskProgress === 'string' && obj.taskProgress.trim()
    ? obj.taskProgress.trim()
    : undefined
  const sandboxPath = typeof obj.sandboxPath === 'string' && obj.sandboxPath.trim()
    ? obj.sandboxPath.trim()
    : undefined
  const sandboxSearchRoot = typeof obj.sandboxSearchRoot === 'string' && obj.sandboxSearchRoot.trim()
    ? obj.sandboxSearchRoot.trim()
    : undefined
  const spawnPrompt = typeof obj.spawnPrompt === 'string' && obj.spawnPrompt.trim()
    ? obj.spawnPrompt.trim()
    : undefined
  const workerRunId = typeof obj.workerRunId === 'string' && obj.workerRunId.trim()
    ? obj.workerRunId.trim()
    : undefined
  const cancellable = obj.cancellable === true ? true : undefined
  const editDiff = parseEditDiff(obj.editDiff)
  const decision = parseDecision(obj.decision)
  const routingTraces = parseRoutingTraces(obj.routingTraces)
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
    && !tasks?.length
    && !taskQueue?.length
    && taskRevision == null
    && !taskProgress
    && !sandboxPath
    && !sandboxSearchRoot
    && !spawnPrompt
    && !workerRunId
    && !cancellable
    && !editDiff
    && !decision
    && !routingTraces?.length
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
    tasks,
    taskQueue,
    taskRevision,
    taskProgress,
    sandboxPath,
    sandboxSearchRoot,
    spawnPrompt,
    workerRunId,
    cancellable,
    editDiff,
    decision,
    routingTraces,
  }
}

/** upsert 时合并 metadata（含 HITL/Recovery） */
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
  const prevRevision = prev.taskRevision ?? 0
  const incomingRevision = incoming.taskRevision ?? 0
  if (incoming.tasks?.length && incomingRevision >= prevRevision) {
    merged.tasks = incoming.tasks
    merged.taskRevision = incoming.taskRevision
    merged.taskProgress = incoming.taskProgress ?? merged.taskProgress
  }
  // harness H1：taskQueue 全量替换（无 revision；有则覆盖）
  if (incoming.taskQueue?.length) {
    merged.taskQueue = incoming.taskQueue
    merged.taskProgress = incoming.taskProgress ?? merged.taskProgress
  }
  if (incoming.decision || prev.decision) {
    merged.decision = {
      ...prev.decision,
      ...incoming.decision,
      questions: incoming.decision?.questions?.length
        ? incoming.decision.questions
        : prev.decision?.questions,
      answers: incoming.decision?.answers !== undefined
        ? incoming.decision.answers
        : prev.decision?.answers,
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
  // 无 summary 不丢弃：worker/subagent 骨架步仅有 label+phase，由 WorkerTimelineBridge.begin() 下发，
  // 丢弃会导致整卡不渲染（subSteps 随父步一并丢失）
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
    stepSummary: typeof raw.stepSummary === 'string' && raw.stepSummary.trim() ? raw.stepSummary : undefined,
  }
}
