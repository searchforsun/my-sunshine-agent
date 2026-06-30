/** 时间线步骤排序 */
import type { ProcessingStep, StepPhase } from './processingSteps'

export const STEP_ORDER: StepPhase[] = [
  'intent', 'skill', 'plan', 'node', 'rag', 'tool', 'agent', 'think', 'generate',
]

function isThinkStepId(id: string): boolean {
  return id === 'think' || id.startsWith('think-')
}

/** plan-workflow / 静态 workflow 的节点级 reasoning，不走 ReAct think 步骤 */
export function isWorkflowNodeStepId(id: string | undefined): boolean {
  return !!id && id.startsWith('node-')
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

export { isThinkStepId }
