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

}



export const STEP_ORDER: StepPhase[] = ['intent', 'plan', 'node', 'rag', 'tool', 'agent', 'think', 'generate']

/** 步骤标题：优先用后端 label，保留 think/plan 等固定 phase 本地文案 */
export function formatStepLabel(step: ProcessingStep): string {
  if (step.label?.trim()) {
    return step.label
  }
  if (step.id.startsWith('tool-')) {
    return step.id
  }
  if (step.id.startsWith('node-')) {
    return step.id
  }
  if (step.id === 'think' || step.id.startsWith('think-')) {
    return step.id === 'think' ? '规划推理' : '综合分析'
  }
  if (step.id === 'plan') {
    return '执行计划'
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



/** 主行摘要：与标题重复时不展示，避免「生成回答 生成回答」 */
export function resolveStepHeaderText(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  const title = formatStepLabel(step)
  let header = ''
  if (lifecycle === 'running') {
    header = step.summary?.active?.trim() || step.label?.trim() || ''
  } else if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    header = step.summary?.after?.trim()
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



/** 是否有可下拉展开的实际内容（不含主行已展示的 summary 一行） */
export function hasExpandableContent(step: ProcessingStep): boolean {
  const header = resolveStepHeaderText(step)
  if (step.reasoning?.trim()) return true
  if (step.output?.trim()) return true
  if (step.detail?.trim() && step.detail.trim() !== header) return true
  if (step.result?.trim() && step.result.trim() !== header) return true
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



export function upsertStep(steps: ProcessingStep[], incoming: ProcessingStep): ProcessingStep[] {

  const idx = steps.findIndex(s => s.id === incoming.id)

  const next = [...steps]

  if (idx >= 0) {

    const prev = next[idx]

    const merged: ProcessingStep = {

      ...prev,

      ...incoming,

      reasoning: longerText(prev.reasoning, incoming.reasoning),

      output: longerText(prev.output, incoming.output),

      result: incoming.result ?? prev.result,

      durationMs: incoming.durationMs ?? prev.durationMs,

      startedAt: incoming.startedAt ?? prev.startedAt,

      endedAt: incoming.endedAt ?? prev.endedAt,

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
  if (chunk.startsWith(existing)) {
    if (chunk.length <= existing.length) return existing
    const suffix = chunk.slice(existing.length)
    if (suffix === existing || (suffix.length > 40 && existing.includes(suffix))) return existing
    return chunk
  }
  if (existing.startsWith(chunk)) return existing
  if (existing.endsWith(chunk)) return existing
  if (chunk.length > 80 && existing.includes(chunk)) return existing
  const overlap = longestSuffixPrefixOverlap(existing, chunk)
  if (overlap > 0) return existing + chunk.slice(overlap)
  return existing + chunk
}

function longestSuffixPrefixOverlap(prev: string, incoming: string): number {
  const max = Math.min(prev.length, incoming.length)
  for (let len = max; len > 0; len--) {
    if (prev.slice(-len) === incoming.slice(0, len)) return len
  }
  return 0
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

      label: '思考过程',

      reasoning: orphanedReasoning,

      summary: { after: '思考完成' },

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



/** 无 step 事件时的降级占位 */

export function derivePlaceholderSteps(loading: boolean): ProcessingStep[] {

  if (!loading) return []

  return [{

    id: 'generate',

    phase: 'generate',

    lifecycle: 'running',

    status: 'running',

    label: '生成回答',

    summary: { active: '正在撰写回复' },

  }]

}



export function hasActiveStep(steps: ProcessingStep[] | undefined): boolean {

  return !!steps?.some(s => (s.lifecycle ?? s.status) === 'running')

}


