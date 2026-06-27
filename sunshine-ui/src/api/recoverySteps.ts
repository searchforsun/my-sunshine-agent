import type { ProcessingStep } from './processingSteps'
import {
  hasHitlPanel,
  hitlConfirmationForStep,
  isHitlAwaiting,
  isHitlSummaryAwaiting,
  resolveStepForHitlDisplay,
  type HitlConfirmationPayload,
} from './hitlSteps'

function isTerminalNodeStep(step: ProcessingStep): boolean {
  const lc = step.lifecycle ?? step.status
  return lc === 'done' || lc === 'skipped' || lc === 'terminated'
}

/** 节点或 subSteps 内是否有待确认写工具（终态节点上的 HITL 视为脏数据） */
export function stepHasHitlAwaiting(step?: ProcessingStep): boolean {
  if (!step) return false
  if (isTerminalNodeStep(step)) return false
  if (isHitlAwaiting(step)) return true
  return step.subSteps?.some(s => isHitlAwaiting(s)) ?? false
}

/** 节点或 subSteps 内承载 HITL 面板的步骤（含 pending / summary 等待态） */
export function findHitlStep(
  step?: ProcessingStep,
  pending?: HitlConfirmationPayload,
): ProcessingStep | undefined {
  if (!step) return undefined
  for (const sub of step.subSteps ?? []) {
    if (hasHitlPanel(sub) || isHitlSummaryAwaiting(sub) || hitlConfirmationForStep(sub, pending)) {
      return resolveStepForHitlDisplay(sub, pending)
    }
  }
  if (hasHitlPanel(step) || isHitlSummaryAwaiting(step) || hitlConfirmationForStep(step, pending)) {
    return resolveStepForHitlDisplay(step, pending)
  }
  return undefined
}

export function isRecoveryAwaiting(step?: ProcessingStep): boolean {
  return step?.metadata?.recoveryStatus === 'awaiting'
    && typeof step.metadata?.recoveryToken === 'string'
    && !!step.metadata.recoveryToken.trim()
}

/** 用户已选择跳过：错误结果已传给下游 */
export function isRecoverySkipped(step?: ProcessingStep): boolean {
  if (step?.metadata?.recoveryStatus === 'skipped') return true
  return (step?.lifecycle ?? step?.status) === 'skipped'
}

/** 用户已选择终止流程 */
export function isRecoveryTerminated(step?: ProcessingStep): boolean {
  if (!step) return false
  if (step.metadata?.recoveryStatus === 'terminated') return true
  return (step.lifecycle ?? step.status) === 'terminated'
}

export function resolveRecoveryError(step?: ProcessingStep): string {
  return step?.metadata?.recoveryError?.trim()
    || step?.summary?.after?.trim()
    || step?.result?.trim()
    || '节点执行失败'
}

/** 乐观更新 recovery metadata */
export function applyRecoveryDecision(
  steps: ProcessingStep[],
  token: string,
  action: 'retry' | 'terminate' | 'skip',
): ProcessingStep[] {
  const status = action === 'retry' ? 'retry' : action === 'skip' ? 'skipped' : 'terminated'
  const idx = steps.findIndex(s => s.metadata?.recoveryToken === token)
  if (idx >= 0) {
    const prev = steps[idx]
    const next = [...steps]
    const summary = prev.summary ?? {}
    if (action === 'terminate') {
      next[idx] = {
        ...prev,
        lifecycle: 'terminated',
        status: 'terminated',
        summary: { ...summary, active: '已终止', after: '已终止' },
        metadata: {
          ...prev.metadata,
          recoveryStatus: status,
          recoveryToken: undefined,
        },
      }
    } else if (action === 'skip') {
      const err = resolveRecoveryError(prev)
      next[idx] = {
        ...prev,
        lifecycle: 'done',
        status: 'done',
        summary: { ...summary, active: '已完成', after: err },
        result: err,
        detail: err,
        metadata: {
          ...prev.metadata,
          recoveryStatus: status,
          recoveryToken: undefined,
        },
      }
    } else {
      next[idx] = {
        ...prev,
        metadata: {
          ...prev.metadata,
          recoveryStatus: status,
          recoveryToken: undefined,
        },
      }
    }
    return next
  }
  let changed = false
  const next = steps.map(step => {
    if (!step.subSteps?.length) return step
    const subNext = applyRecoveryDecision(step.subSteps, token, action)
    if (subNext === step.subSteps) return step
    changed = true
    return { ...step, subSteps: subNext }
  })
  return changed ? next : steps
}
