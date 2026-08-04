/** 时间线步骤排序 */
import type { ProcessingStep, StepPhase } from './processingSteps'

/** ReAct 设计序：intent -> tasks -> think* -> tool*（tasks 紧跟意图识别，在 think 之前） */
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

/** tasks 步固定紧跟 intent（意图识别之后、think 之前） */
function repositionTasksAfterIntent(steps: ProcessingStep[]): ProcessingStep[] {
  const tasksIdx = steps.findIndex(s => s.phase === 'tasks')
  if (tasksIdx < 0) return steps
  const intentIdx = steps.findIndex(s => s.phase === 'intent')
  if (intentIdx < 0) return steps
  const targetIdx = intentIdx + 1
  if (tasksIdx === targetIdx) return steps
  const tasksStep = steps[tasksIdx]
  const without = steps.filter((_, i) => i !== tasksIdx)
  const intentPos = without.findIndex(s => s.phase === 'intent')
  const insertAt = intentPos + 1
  return [...without.slice(0, insertAt), tasksStep, ...without.slice(insertAt)]
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
  return repositionTasksAfterIntent(sorted)
}

export { isThinkStepId }
