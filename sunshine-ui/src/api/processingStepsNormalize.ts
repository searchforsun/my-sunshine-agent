/** 时间线步骤排序 */
import type { ProcessingStep, StepPhase } from './processingSteps'

/** ReAct 设计序：think* → tasks → tool*（同 startedAt 时 tasks 不得排在 tool 之后） */
export const STEP_ORDER: StepPhase[] = [
  'intent', 'skill', 'plan', 'node', 'rag', 'think', 'tasks', 'tool', 'agent', 'generate',
]

function isThinkStepId(id: string): boolean {
  return id === 'think' || id.startsWith('think-')
}

/** plan-workflow / 静态 workflow 的节点级 reasoning，不走 ReAct think 步骤 */
export function isWorkflowNodeStepId(id: string | undefined): boolean {
  return !!id && id.startsWith('node-')
}

/** tasks 步固定紧跟首个 think，展示在「规划推理」下方、工具调用之前 */
function repositionTasksAfterFirstThink(steps: ProcessingStep[]): ProcessingStep[] {
  const tasksIdx = steps.findIndex(s => s.phase === 'tasks')
  if (tasksIdx < 0) return steps
  const firstThinkIdx = steps.findIndex(s => isThinkStepId(s.id))
  if (firstThinkIdx < 0) return steps
  const targetIdx = firstThinkIdx + 1
  if (tasksIdx === targetIdx) return steps
  const tasksStep = steps[tasksIdx]
  const without = steps.filter((_, i) => i !== tasksIdx)
  const thinkPos = without.findIndex(s => isThinkStepId(s.id))
  const insertAt = thinkPos + 1
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
  return repositionTasksAfterFirstThink(sorted)
}

export { isThinkStepId }
