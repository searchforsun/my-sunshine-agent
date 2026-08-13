/** 区分 harness 分层时间线 vs 静态 Workflow DAG */
import type { ProcessingStep } from './processingSteps'
import { resolvePlanIdFromStep } from './processingStepsPlan'

function hasWorkerStep(steps: ProcessingStep[]): boolean {
  return steps.some(s => s.phase === 'worker' || /^worker-/.test(s.id))
}

function hasResolvablePlanId(steps: ProcessingStep[]): boolean {
  return steps.some(s => s.phase === 'plan' && !!resolvePlanIdFromStep(s))
}

function hasNodeSteps(steps: ProcessingStep[]): boolean {
  return steps.some(s => s.id.startsWith('node-'))
}

/**
 * 有可渲染 DAG 图 → 静态 Workflow。
 * classic planId/executionPlanId + node-*（或无 worker 的历史 plan 引用）。
 */
export function isPlanDagMessage(
  steps: ProcessingStep[],
  executionPlanId?: string | null,
): boolean {
  // harness worker 且无图 → 非 DAG（互斥时图已在上方胜出）
  if (hasWorkerStep(steps)) return false
  const hasPlanRef = hasResolvablePlanId(steps) || !!executionPlanId?.trim()
  if (!hasPlanRef) return false
  // classic：plan 引用 + node-*；历史仅有 planId/executionPlanId 合成 plan 亦可 DAG
  return hasNodeSteps(steps) || steps.some(s => s.phase === 'plan')
}

/**
 * harness：worker 步，或有 plan 且无 DAG。
 * DAG 优先：`isPlanDagMessage(steps, executionPlanId)` 为真时恒为 false。
 */
export function isHarnessTimelineMessage(
  steps: ProcessingStep[],
  executionPlanId?: string | null,
): boolean {
  if (isPlanDagMessage(steps, executionPlanId)) return false
  if (hasWorkerStep(steps)) return true
  if (steps.some(s => s.phase === 'plan')) return true
  return false
}
