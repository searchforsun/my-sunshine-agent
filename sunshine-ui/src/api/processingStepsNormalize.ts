/** 时间线步骤排序 */
import type { ProcessingStep, StepPhase } from './processingSteps'

/** ReAct 设计序：intent → skill → tasks → think* → tool*（skill 固定第二步；tasks 在 think 前） */
export const STEP_ORDER: StepPhase[] = [
  'intent', 'skill', 'plan', 'node', 'rag', 'tasks', 'think', 'tool', 'agent', 'generate',
]

function isThinkStepId(id: string): boolean {
  return id === 'think' || id.startsWith('think-')
}

/** plan-workflow / 静态 workflow 的节点级 reasoning，不走 ReAct think 步骤 */
export function isWorkflowNodeStepId(id: string | undefined): boolean {
  return !!id && id.startsWith('node-')
}

/** 将指定 phase 步移到 anchorPhase 之后（若不存在则原样返回） */
function movePhaseAfterAnchor(
  steps: ProcessingStep[],
  phase: StepPhase,
  anchorPhase: StepPhase,
): ProcessingStep[] {
  const phaseIdx = steps.findIndex(s => s.phase === phase)
  if (phaseIdx < 0) return steps
  const anchorIdx = steps.findIndex(s => s.phase === anchorPhase)
  if (anchorIdx < 0) return steps
  if (phaseIdx === anchorIdx + 1) return steps
  const moved = steps[phaseIdx]
  const without = steps.filter((_, i) => i !== phaseIdx)
  const newAnchor = without.findIndex(s => s.phase === anchorPhase)
  const insertAt = newAnchor + 1
  return [...without.slice(0, insertAt), moved, ...without.slice(insertAt)]
}

/**
 * 头部钉扎：skill 固定为第二步（intent 之后）；
 * tasks 紧随 skill（无 skill 则紧随 intent），始终在 think 之前。
 */
function repositionPinnedHeaderSteps(steps: ProcessingStep[]): ProcessingStep[] {
  let next = movePhaseAfterAnchor(steps, 'skill', 'intent')
  const hasSkill = next.some(s => s.phase === 'skill')
  next = movePhaseAfterAnchor(next, 'tasks', hasSkill ? 'skill' : 'intent')
  return next
}

export function sortSteps(steps: ProcessingStep[]): ProcessingStep[] {
  const sorted = [...steps].sort((a, b) => {
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
  return repositionPinnedHeaderSteps(sorted)
}

export { isThinkStepId }
