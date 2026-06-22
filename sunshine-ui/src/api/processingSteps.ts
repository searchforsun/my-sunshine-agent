/**

 * 后端处理流水线步骤 — SSE type:step (V2: lifecycle + summary + duration)

 */



export type StepPhase = 'intent' | 'rag' | 'agent' | 'think' | 'generate' | string

export type StepStatus = 'pending' | 'running' | 'done' | 'error' | 'skipped'

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

  /** @deprecated V1 兼容 */

  status?: StepStatus

  /** @deprecated V1 兼容 */

  label?: string

  metadata?: StepMetadata

}



export const STEP_ORDER: StepPhase[] = ['intent', 'plan', 'node', 'rag', 'tool', 'agent', 'think', 'generate']

/** 步骤标题：仅用后端 SSE 下发的 step.label，无则回退 step.id */
export function formatStepLabel(step: ProcessingStep): string {
  if (step.label?.trim()) {
    return step.label
  }
  return step.id
}



function parseSummary(raw: unknown): StepSummary | undefined {

  if (!raw || typeof raw !== 'object') return undefined

  const obj = raw as Record<string, unknown>

  const before = typeof obj.before === 'string' ? obj.before : undefined

  const active = typeof obj.active === 'string' ? obj.active : undefined

  const after = typeof obj.after === 'string' ? obj.after : undefined

  if (!before && !active && !after) return undefined

  return { before, active, after }

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
  if (
    hitCount == null
    && (!sources || sources.length === 0)
    && !rewriteApplied
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
  }
}



export function normalizeStep(raw: Record<string, unknown>): ProcessingStep | null {

  if (typeof raw.id !== 'string') return null

  const phase = (typeof raw.phase === 'string' ? raw.phase : 'generate') as StepPhase

  const lifecycle = (

    typeof raw.lifecycle === 'string' ? raw.lifecycle

      : typeof raw.status === 'string' ? raw.status

        : 'running'

  ) as StepLifecycle

  const status = (typeof raw.status === 'string' ? raw.status : lifecycle) as StepStatus

  const label = typeof raw.label === 'string' ? raw.label : undefined

  if (!label && !raw.summary) return null



  const step: ProcessingStep = {

    id: raw.id,

    phase,

    lifecycle,

    summary: parseSummary(raw.summary),

    startedAt: typeof raw.startedAt === 'number' ? raw.startedAt : undefined,

    endedAt: typeof raw.endedAt === 'number' ? raw.endedAt : undefined,

    durationMs: typeof raw.durationMs === 'number' ? raw.durationMs : undefined,

    detail: typeof raw.detail === 'string' ? raw.detail : undefined,

    reasoning: typeof raw.reasoning === 'string' ? raw.reasoning : undefined,

    output: typeof raw.output === 'string' ? raw.output : undefined,

    result: typeof raw.result === 'string' ? raw.result : undefined,

    ts: typeof raw.ts === 'number' ? raw.ts : undefined,

    status,

    label,

    metadata: parseMetadata(raw.metadata),

  }



  return migrateV1Step(step)

}



export function migrateV1Step(step: ProcessingStep): ProcessingStep {

  if (step.summary) return step

  const lifecycle = step.lifecycle ?? step.status ?? 'running'

  const label = step.label ?? step.id

  return {

    ...step,

    lifecycle,

    label,

    summary: {

      before: lifecycle === 'pending' ? label : undefined,

      active: lifecycle === 'running' ? label : undefined,

      after: lifecycle === 'done' ? (step.detail ?? label) : undefined,

    },

  }

}



export function formatDuration(ms?: number): string {

  if (ms == null) return ''

  if (ms < 1) return '<1ms'

  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`

}



/** 步骤 lifecycle（兼容 status） */
export function stepLifecycle(step: ProcessingStep): StepLifecycle {
  return (step.lifecycle ?? step.status ?? 'pending') as StepLifecycle
}



/** 展示后端下发的 metadata（如 RAG 命中数与来源文档） */
export function formatStepMetadata(step: ProcessingStep): string {
  const m = step.metadata
  if (!m) return ''
  const parts: string[] = []
  if (typeof m.hitCount === 'number') {
    parts.push(`命中 ${m.hitCount} 条`)
  }
  const sources = m.sources?.filter(s => s.trim())
  if (sources?.length) {
    parts.push(`来源：${sources.join('、')}`)
  }
  return parts.join('，')
}

/** 从 metadata 构造 Query 改写展开文案（与后端 detail 格式对齐） */
export function formatRewriteMetadata(step: ProcessingStep): string {
  const m = step.metadata
  if (!m?.rewriteApplied || !m.rewriteFrom || !m.rewriteTo) return ''
  const targetLabel = m.rewriteScenario === 'hyde' ? 'HyDE' : '改写后'
  const latency = typeof m.rewriteLatencyMs === 'number'
    ? `\n耗时：${m.rewriteLatencyMs}ms`
    : ''
  const body = `改写前：${m.rewriteFrom}\n${targetLabel}：${m.rewriteTo}${latency}`
  if (m.rewriteScenarioLabel?.trim()) {
    return `${m.rewriteScenarioLabel.trim()}\n${body}`
  }
  return body
}



/** 主行摘要最大可见字数，超出部分折叠到展开区 */
export const STEP_HEADER_PREVIEW_MAX = 42



function truncateStepPreview(text: string, max = STEP_HEADER_PREVIEW_MAX): string {
  if (text.length <= max) return text
  return `${text.slice(0, max)}…`
}



/** 主行摘要全文（不截断，供展开区使用） */
export function resolveStepSummaryFull(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  const title = formatStepLabel(step)
  let header = ''
  if (lifecycle === 'running') {
    header = step.summary?.active?.trim() || step.label?.trim() || ''
  } else if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    header = formatStepMetadata(step)
      || step.summary?.after?.trim()
      || step.result?.trim()
      || step.detail?.trim()
      || ''
  } else {
    header = step.summary?.before?.trim() || step.label?.trim() || ''
  }
  if (!header || header === title) {
    return ''
  }
  return header
}



/** 主行摘要：过长时截断为一行预览，完整内容在展开区（detail/result） */
export function resolveStepHeaderText(step: ProcessingStep): string {
  const full = resolveStepSummaryFull(step)
  const oneLine = full.replace(/\s+/g, ' ').trim()
  return truncateStepPreview(oneLine)
}

/** 从 Markdown 正文中提取首条中文叙述行（用于补全后端历史截断的 after） */
function extractFirstProseLine(text: string): string {
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#') || /^\|/.test(line)) continue
    if (/^[-*_]{3,}$/.test(line)) continue
    const plain = line.replace(/\*\*|__|`/g, '').replace(/^>\s*/, '').trim()
    if (plain.length >= 8 && /[\u4e00-\u9fff]/.test(plain)) {
      return plain.replace(/\s+/g, ' ')
    }
  }
  return ''
}

/** 展开区首行：完整 summary.after（主行预览的下移全文，不做任何截断） */
export function resolveStepExpandSummary(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  let oneLine = ''
  if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    oneLine = (step.summary?.after?.trim() || resolveStepSummaryFull(step)).replace(/\s+/g, ' ').trim()
  } else {
    oneLine = resolveStepSummaryFull(step).replace(/\s+/g, ' ').trim()
  }
  if (oneLine.endsWith('…') && step.detail?.trim()) {
    const fromDetail = extractFirstProseLine(step.detail)
    const prefix = oneLine.slice(0, -1).trim()
    if (fromDetail && (fromDetail.startsWith(prefix) || prefix.length >= 12 && fromDetail.startsWith(prefix.slice(0, 12)))) {
      return fromDetail
    }
  }
  return oneLine
}

/** 展开区正文：detail/result（如 Agent Markdown、Query 改写），与 after 摘要区分 */
export function resolveStepExpandBody(step: ProcessingStep): string {
  const summary = resolveStepExpandSummary(step)
  const detail = step.detail?.trim()
  if (detail && detail !== summary) return detail
  const rewrite = formatRewriteMetadata(step)
  if (rewrite && rewrite !== summary && !(detail && detail.includes('改写前：'))) return rewrite
  const result = step.result?.trim()
  if (result && result !== summary && result !== detail) return result
  return ''
}

/** @deprecated 使用 resolveStepExpandSummary + resolveStepExpandBody */
export function resolveStepExpandText(step: ProcessingStep): string {
  return resolveStepExpandBody(step) || resolveStepExpandSummary(step)
}

/** 主行是否将摘要下移到展开区（展开后主行仅保留 label） */
export function shouldShiftSummaryOnExpand(step: ProcessingStep): boolean {
  const summary = resolveStepExpandSummary(step)
  if (!summary) return false
  return summary !== resolveStepHeaderText(step) || summary.length > STEP_HEADER_PREVIEW_MAX
}

/** 是否有可下拉展开的实际内容 */
export function hasExpandableContent(step: ProcessingStep): boolean {
  if (shouldShiftSummaryOnExpand(step)) return true
  if (resolveStepExpandBody(step)) return true
  if (formatRewriteMetadata(step)) return true
  if (step.reasoning?.trim()) return true
  if (step.output?.trim()) return true
  return false
}



/** 优先 durationMs，否则由 startedAt / endedAt 推算 */
export function resolveStepDurationMs(step: ProcessingStep): number | undefined {

  if (step.durationMs != null && step.durationMs >= 0) {

    return step.durationMs

  }

  if (step.startedAt != null && step.endedAt != null && step.endedAt >= step.startedAt) {

    return step.endedAt - step.startedAt

  }

  return undefined

}



export function totalDuration(steps: ProcessingStep[]): number {

  return steps

    .filter(s => (s.lifecycle ?? s.status) === 'done')

    .reduce((sum, s) => sum + (resolveStepDurationMs(s) ?? 0), 0)

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



export function upsertStep(steps: ProcessingStep[], incoming: ProcessingStep): ProcessingStep[] {

  const idx = steps.findIndex(s => s.id === incoming.id)

  const next = [...steps]

  if (idx >= 0) {

    const prev = next[idx]

    const lifecycle = incoming.lifecycle ?? prev.lifecycle
    const status = incoming.status ?? prev.status

    const merged: ProcessingStep = {

      ...prev,

      ...incoming,

      summary: mergeSummary(prev.summary, incoming.summary, lifecycle),

      reasoning: longerText(prev.reasoning, incoming.reasoning),

      output: longerText(prev.output, incoming.output),

      result: incoming.result ?? prev.result,

      detail: incoming.detail ?? prev.detail,

      metadata: incoming.metadata ?? prev.metadata,

      durationMs: incoming.durationMs ?? prev.durationMs,

      startedAt: incoming.startedAt ?? prev.startedAt,

      endedAt: incoming.endedAt ?? prev.endedAt,

      lifecycle,

      status,

    }

    merged.durationMs = resolveStepDurationMs(merged) ?? merged.durationMs

    next[idx] = merged

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

  const base: ProcessingStep = idx >= 0 ? { ...steps[idx] } : {

    id: delta.stepId,

    phase: delta.stepId as StepPhase,

    lifecycle: 'running',

    status: 'running',

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

      base.result = delta.text

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

function isThinkStepId(id: string): boolean {
  return id === 'think' || id.startsWith('think-')
}



export function findRunningStepId(steps: ProcessingStep[]): string | undefined {

  for (const id of REASONING_STEP_PRIORITY) {

    const step = steps.find(s => s.id === id)

    if (step && (step.lifecycle === 'running' || step.status === 'running')) {

      return id

    }

  }

  const runningThink = steps.find(s => isThinkStepId(s.id)
    && (s.lifecycle === 'running' || s.status === 'running'))

  if (runningThink) return runningThink.id

  return steps.find(s => s.lifecycle === 'running' || s.status === 'running')?.id

}



/** 将 message / generate 上的 reasoning 归并到独立 think 步骤（历史数据兼容） */

export function normalizeTimelineSteps(

  steps: ProcessingStep[],

  reasoning?: string,

): ProcessingStep[] {

  if (steps.length === 0) return steps

  let result = [...steps]

  const hasThink = result.some(s => isThinkStepId(s.id))

  const agentHasReasoning = result.some(s => s.id === 'agent' && !!s.reasoning?.trim())

  // Agent 路径思考已挂在「分析作答」；勿再用 message.reasoning 合成独立 think
  if (hasThink || agentHasReasoning) {

    return sortSteps(result)

  }

  const genIdx = result.findIndex(s => s.id === 'generate')

  const gen = genIdx >= 0 ? result[genIdx] : undefined

  const orphanedReasoning = gen?.reasoning?.trim() || reasoning?.trim()

  if (!hasThink && orphanedReasoning) {

    const thinkStep: ProcessingStep = {

      id: 'think',

      phase: 'think',

      lifecycle: 'done',

      status: 'done',

      reasoning: orphanedReasoning,

    }

    if (gen && gen.reasoning) {

      const { reasoning: _removed, ...genWithoutReasoning } = gen

      result[genIdx] = genWithoutReasoning

    }

    result = [...result, thinkStep]

  }

  return sortSteps(result)

}



function longerText(a?: string, b?: string): string | undefined {

  if (!a) return b

  if (!b) return a

  if (b.length >= a.length && b.startsWith(a)) return b

  if (a.length >= b.length && a.startsWith(b)) return a

  return a + b

}



export function sortSteps(steps: ProcessingStep[]): ProcessingStep[] {

  return [...steps].sort((a, b) => {

    const aStart = a.startedAt ?? a.ts ?? 0

    const bStart = b.startedAt ?? b.ts ?? 0

    if (aStart !== bStart) return aStart - bStart

    const ai = STEP_ORDER.indexOf(a.phase)

    const bi = STEP_ORDER.indexOf(b.phase)

    const aOrder = ai >= 0 ? ai : STEP_ORDER.length

    const bOrder = bi >= 0 ? bi : STEP_ORDER.length

    if (aOrder !== bOrder) return aOrder - bOrder

    return a.id.localeCompare(b.id)

  })

}



export function summarizeSteps(steps: ProcessingStep[]): string {

  const parts = steps

    .filter(s => (s.lifecycle ?? s.status) === 'done')

    .map(s => {

      if (s.summary?.after) return s.summary.after

      const label = s.label ?? s.id

      return s.detail ? `${label} · ${s.detail}` : label

    })

    .filter(Boolean)



  const total = totalDuration(steps)

  if (total > 0) parts.push(formatDuration(total))



  return parts.join(' · ')

}



export function hasActiveStep(steps: ProcessingStep[] | undefined): boolean {

  return !!steps?.some(s => (s.lifecycle ?? s.status) === 'running')

}


