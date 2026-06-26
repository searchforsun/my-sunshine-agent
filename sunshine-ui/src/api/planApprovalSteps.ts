import type { ProcessingStep, StepMetadata } from './processingSteps'

export interface PlanApprovalRoundView {
  roundNo: number
  status: 'awaiting' | 'approved' | 'regenerated' | 'timed_out'
  userHint?: string
  chainSummary?: string
  createdAt?: number
  resolvedAt?: number
}

export interface PlanApprovalView {
  status?: 'awaiting' | 'approved'
  token?: string
  expiresAt?: number
  rounds?: PlanApprovalRoundView[]
}

export function resolvePlanApproval(meta?: StepMetadata): PlanApprovalView | undefined {
  if (!meta?.planApproval) return undefined
  return meta.planApproval
}

export function isPlanApprovalAwaiting(step: ProcessingStep): boolean {
  return resolvePlanApproval(step.metadata)?.status === 'awaiting'
}

/** plan 步 summary.active 为「正在根据修改意见重新规划…」 */
export function isPlanRegenerating(step: ProcessingStep): boolean {
  const active = step.summary?.active?.trim() ?? ''
  return active.includes('重新规划') || active.includes('重新生成')
}

export function resolvePlanApprovalToken(step: ProcessingStep): string | undefined {
  const token = resolvePlanApproval(step.metadata)?.token?.trim()
  return token || undefined
}

export function resolvePlanApprovalRounds(step: ProcessingStep): PlanApprovalRoundView[] {
  return resolvePlanApproval(step.metadata)?.rounds ?? []
}

export function formatPlanApprovalRoundSummary(round: PlanApprovalRoundView): string {
  if (round.status === 'approved') {
    return '已确认执行'
  }
  if (round.status === 'regenerated') {
    const hint = round.userHint?.trim()
    return hint ? `已重新生成（${hint}）` : '已重新生成'
  }
  if (round.status === 'timed_out') {
    return '确认超时'
  }
  return '等待确认'
}
