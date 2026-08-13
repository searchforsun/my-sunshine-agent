/** harness 分层时间线：plan → worker → handoff 行分组 / 缩进 */
import type { ProcessingStep } from './processingSteps'

export function isWorkerStep(step: { id?: string; phase?: string }): boolean {
  return step.phase === 'worker' || !!step.id?.startsWith('worker-')
}

/** harness 规划步：phase=plan 或 id 为 plan / plan-R{n} */
export function isHarnessPlanStep(step: { id?: string; phase?: string }): boolean {
  if (step.phase === 'plan') return true
  const id = step.id ?? ''
  return id === 'plan' || /^plan-R\d+$/.test(id)
}

/**
 * worker 结束收束文案（handoff 子行）；不发明独立 phase。
 * 优先 result → detail → summary.after；与 label 相同则仍可展示（后端可能只下 status）。
 */
export function resolveWorkerHandoffText(step: ProcessingStep): string | undefined {
  const lc = step.lifecycle ?? 'pending'
  if (lc !== 'done' && lc !== 'error' && lc !== 'paused' && lc !== 'terminated') {
    return undefined
  }
  const label = (step.label ?? '').trim()
  const candidates = [
    step.result?.trim(),
    step.detail?.trim(),
    step.summary?.after?.trim(),
  ].filter((t): t is string => !!t)
  for (const text of candidates) {
    if (text !== label) return text
  }
  return candidates[0]
}

/**
 * 缩进：plan / intent / think / planner-answer = 0；
 * worker 挂在最近前置 plan* 下为 1，无 plan 时仍为 0。
 */
export function resolveHarnessIndentLevels(steps: ProcessingStep[]): Map<string, number> {
  const levels = new Map<string, number>()
  let nearestPlanId: string | undefined
  for (const step of steps) {
    if (isHarnessPlanStep(step)) {
      nearestPlanId = step.id
      levels.set(step.id, 0)
      continue
    }
    if (isWorkerStep(step)) {
      levels.set(step.id, nearestPlanId ? 1 : 0)
      continue
    }
    levels.set(step.id, 0)
  }
  return levels
}

export interface HarnessTimelineEntry {
  step: ProcessingStep
  /** 0 = L0；1 = worker 缩进 */
  indent: 0 | 1
  /** worker done 时的 handoff 子行文案 */
  handoffText?: string
}

/** 将扁平 steps 投影为 harness 层级行（保留原序；不含 tool 分组） */
export function buildHarnessTimelineEntries(steps: ProcessingStep[]): HarnessTimelineEntry[] {
  const indents = resolveHarnessIndentLevels(steps)
  return steps.map((step) => {
    const raw = indents.get(step.id) ?? 0
    const indent: 0 | 1 = raw >= 1 ? 1 : 0
    const handoffText = isWorkerStep(step) ? resolveWorkerHandoffText(step) : undefined
    return { step, indent, handoffText }
  })
}
