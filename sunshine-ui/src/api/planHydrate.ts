/** 历史消息仅有 executionPlanId、无 steps 时，合成 plan 步以渲染 PlanDagPanel */
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'

/** DAG 上所有 node 步（含 node-answer） */
export function isPlanDagNodeStep(step: ProcessingStep): boolean {
  return step.id.startsWith('node-') && step.id !== 'node-answer'
}

export function listPlanDagNodeSteps(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return []
  return steps.filter(isPlanDagNodeStep)
}

export function syntheticPlanStep(executionPlanId: string): ProcessingStep {
  return {
    id: 'plan',
    phase: 'plan',
    lifecycle: 'done',
    detail: `planId=${executionPlanId}`,
    summary: { after: '执行计划' },
  }
}

/** 补全 plan 步，供 OperationStack / ChatView timeline 使用 */
export function ensurePlanTimelineSteps(msg: Pick<ChatMessage, 'steps' | 'executionPlanId'>): ProcessingStep[] {
  const steps = msg.steps ?? []
  if (steps.some(s => s.phase === 'plan')) return steps
  const planId = msg.executionPlanId?.trim()
  if (!planId) return steps
  return [...steps, syntheticPlanStep(planId)]
}

export function hasPlanTimeline(msg: Pick<ChatMessage, 'steps' | 'executionPlanId'>): boolean {
  return ensurePlanTimelineSteps(msg).some(s => s.phase === 'plan')
}
