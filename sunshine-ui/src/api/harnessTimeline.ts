/** 区分 harness 分层时间线 vs 静态 Workflow DAG */
import type { ProcessingStep } from './processingSteps'
import { resolvePlanIdFromStep } from './processingStepsPlan'
import { catalogToolIdFromStepId } from './processingStepsDisplay'

/**
 * 执行单元记号统一为 `T{序号}-{版本}` 大写格式（WorkerCard / TaskBoard 共用）：
 * - r5-quality-2 → T5-2（序号取 taskId 首个数字，版本取末尾 -N）
 * - t1-1 → T1-1
 * - t1-arch → T1（描述后缀非版本，不显示）
 * - 无数字序号时退回 T{版本} / T
 */
export function formatTaskUnitId(taskId: string): string {
  if (!taskId) return ''
  const raw = taskId.trim()
  if (!raw) return ''
  const verMatch = raw.match(/-(?<v>\d+)$/)
  const ver = verMatch ? verMatch.groups!.v : ''
  const base = verMatch ? raw.slice(0, verMatch.index!) : raw
  const numMatch = base.match(/\d+/)
  const unit = numMatch ? numMatch[0] : ''
  if (unit) return ver ? `T${unit}-${ver}` : `T${unit}`
  return ver ? `T-${ver}` : 'T'
}

function hasWorkerStep(steps: ProcessingStep[]): boolean {
  return steps.some(s => s.phase === 'worker' || /^worker-/.test(s.id ?? ''))
}

/** Planner-Executor 元工具步（plan_submit / self_assess / dispatch_worker）——pro 主对话 harness 信号 */
function isPlannerMetaToolStep(step: ProcessingStep): boolean {
  if (step.phase !== 'tool') return false
  const cid = catalogToolIdFromStepId(step.id)
  return cid === 'plan_submit' || cid === 'self_assess' || cid === 'dispatch_worker'
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
 * harness：worker 步、plan 步，或 Planner-Executor 元工具步（plan_submit/self_assess）。
 * DAG 优先：`isPlanDagMessage(steps, executionPlanId)` 为真时恒为 false。
 * Planner 元工具信号兜底：pro 主对话即使 Worker 步未折叠进主时间线（foldActive=false /
 * Worker 独立平铺）也按 harness 分层平铺，禁止 roundGroup 吞掉 planner 的 think/工具行。
 */
export function isHarnessTimelineMessage(
  steps: ProcessingStep[],
  executionPlanId?: string | null,
): boolean {
  if (isPlanDagMessage(steps, executionPlanId)) return false
  if (hasWorkerStep(steps)) return true
  if (steps.some(s => s.phase === 'plan')) return true
  if (steps.some(isPlannerMetaToolStep)) return true
  return false
}
