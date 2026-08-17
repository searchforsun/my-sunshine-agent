/** harness 分层时间线：plan -> worker -> handoff 行分组（worker 不缩进，与工具折叠一致平铺） */
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
 * 优先 result -> detail -> summary.after；与 label 相同则仍可展示（后端可能只下 status）。
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

export interface HarnessTimelineEntry {
  step: ProcessingStep
  /** worker done 时的 handoff 子行文案 */
  handoffText?: string
}

/** 将扁平 steps 投影为 harness 层级行（保留原序；不含 tool 分组） */
export function buildHarnessTimelineEntries(steps: ProcessingStep[]): HarnessTimelineEntry[] {
  return steps.map((step) => ({
    step,
    handoffText: isWorkerStep(step) ? resolveWorkerHandoffText(step) : undefined,
  }))
}
