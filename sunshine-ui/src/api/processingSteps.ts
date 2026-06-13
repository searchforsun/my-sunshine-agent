/**

 * 后端处理流水线步骤 — SSE type:step (V2: lifecycle + summary + duration)

 */



export type StepPhase = 'intent' | 'rag' | 'agent' | 'generate' | string

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

  ts?: number

  /** @deprecated V1 兼容 */

  status?: StepStatus

  /** @deprecated V1 兼容 */

  label?: string

}



export const STEP_ORDER: StepPhase[] = ['intent', 'rag', 'agent', 'generate']



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



export function totalDuration(steps: ProcessingStep[]): number {

  return steps

    .filter(s => (s.lifecycle ?? s.status) === 'done' && s.durationMs != null)

    .reduce((sum, s) => sum + (s.durationMs ?? 0), 0)

}



export function upsertStep(steps: ProcessingStep[], incoming: ProcessingStep): ProcessingStep[] {

  const idx = steps.findIndex(s => s.id === incoming.id)

  const next = [...steps]

  if (idx >= 0) {

    next[idx] = { ...next[idx], ...incoming }

  } else {

    next.push(incoming)

  }

  return sortSteps(next)

}



export function sortSteps(steps: ProcessingStep[]): ProcessingStep[] {

  return [...steps].sort((a, b) => {

    const ai = STEP_ORDER.indexOf(a.phase)

    const bi = STEP_ORDER.indexOf(b.phase)

    const aOrder = ai >= 0 ? ai : STEP_ORDER.length

    const bOrder = bi >= 0 ? bi : STEP_ORDER.length

    if (aOrder !== bOrder) return aOrder - bOrder

    return (a.ts ?? 0) - (b.ts ?? 0)

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

    summary: { active: '生成回答' },

  }]

}



export function hasActiveStep(steps: ProcessingStep[] | undefined): boolean {

  return !!steps?.some(s => (s.lifecycle ?? s.status) === 'running')

}


