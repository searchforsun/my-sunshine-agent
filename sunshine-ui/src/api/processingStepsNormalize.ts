/** 时间线步骤排序与历史数据归一化 */
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

/** 将 message / generate 上的 reasoning 归并到独立 think 步骤（历史数据兼容） */
export function normalizeTimelineSteps(
  steps: ProcessingStep[],
  reasoning?: string,
): ProcessingStep[] {
  if (steps.length === 0) return steps
  let result = [...steps]
  const hasThink = result.some(s => isThinkStepId(s.id))
  const agentHasReasoning = result.some(s => s.id === 'agent' && !!s.reasoning?.trim())
  const workflowPath = result.some(s => s.phase === 'plan' || isWorkflowNodeStepId(s.id))
  // Agent / workflow：思考挂在 agent 或 node-*；勿再用 message.reasoning 合成 ReAct think
  if (hasThink || agentHasReasoning || workflowPath) {
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

export { isThinkStepId }
