import type { ProcessingStep, StepMetadata } from './processingSteps'

/** 续跑 SSE 重放：已完成的 intent/plan 不应再被 pending/running 覆盖 */
export function shouldIgnoreResumeStepReplay(steps: ProcessingStep[], incoming: ProcessingStep): boolean {
  const existing = steps.find(s => s.id === incoming.id)
  if (!existing) return false
  if (incoming.id !== 'intent' && incoming.id !== 'plan') return false
  const wasDone = existing.lifecycle === 'done'
  const regresses = incoming.lifecycle === 'pending'
    || incoming.lifecycle === 'running'
  return wasDone && regresses
}

/** 用户停止生成：running / HITL·Recovery 待确认 的 workflow 节点标为 paused */
export function pauseRunningWorkflowNodes(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return steps ?? []
  return steps.map(step => {
    let next = step
    if (step.subSteps?.length) {
      const subs = pauseRunningWorkflowNodes(step.subSteps)
      if (subs !== step.subSteps) next = { ...next, subSteps: subs }
    }
    if (next.id.startsWith('node-') && shouldPauseStepOnStop(next)) {
      next = toPausedStep(next)
      return next
    }
    const phase = next.phase ?? ''
    if (shouldPauseStepOnStop(next)
        && !next.id.startsWith('node-')
        && (phase === 'think' || phase === 'agent' || phase === 'generate'
            || phase.startsWith('think') || phase.startsWith('tool'))) {
      next = toPausedStep(next)
    }
    return next
  })
}

function shouldPauseStepOnStop(step: ProcessingStep): boolean {
  const lc = step.lifecycle ?? ''
  if (lc === 'running') return true
  return isAwaitingInteractionStep(step)
}

function isAwaitingInteractionStep(step: ProcessingStep): boolean {
  if (step.metadata?.hitlStatus === 'awaiting') return true
  if (step.metadata?.recoveryStatus === 'awaiting') return true
  return false
}

function toPausedStep(step: ProcessingStep): ProcessingStep {
  const now = Date.now()
  return {
    ...step,
    lifecycle: 'paused',
    summary: {
      ...step.summary,
      active: '已暂停',
      after: '已暂停',
    },
    endedAt: now,
    durationMs: step.startedAt != null ? now - step.startedAt : step.durationMs,
  }
}

function stripResumeInteractionMetadata(meta?: StepMetadata): StepMetadata | undefined {
  if (!meta) return undefined
  const {
    hitlStatus,
    hitlToken,
    hitlToolDisplayName,
    hitlParamsSummary,
    hitlExpiresAt,
    recoveryStatus,
    recoveryToken,
    recoveryError,
    recoveryExpiresAt,
    ...rest
  } = meta
  return Object.keys(rest).length > 0 ? rest : undefined
}

function toPendingResumeStep(step: ProcessingStep): ProcessingStep {
  return {
    ...step,
    lifecycle: 'pending',
    metadata: stripResumeInteractionMetadata(step.metadata),
    summary: step.summary?.before ? { before: step.summary.before } : undefined,
    startedAt: undefined,
    endedAt: undefined,
    durationMs: undefined,
  }
}

function reactivatePausedStepIfNeeded(step: ProcessingStep): ProcessingStep {
  let next = step
  if (step.subSteps?.length) {
    const subs = reactivatePausedStepsForResume(step.subSteps)
    if (subs !== step.subSteps) next = { ...next, subSteps: subs }
  }
  const lc = next.lifecycle
  if (lc !== 'paused') return next
  if (next.id.startsWith('node-')) return toPendingResumeStep(next)
  const phase = next.phase ?? ''
  if (phase === 'think' || phase === 'agent' || phase === 'generate'
      || phase.startsWith('think') || phase.startsWith('tool')) {
    return toPendingResumeStep(next)
  }
  return next
}

/** 续跑开始：paused 节点重置为 pending（等待中），清除暂停前 HITL/Recovery 态 */
export function reactivatePausedStepsForResume(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return steps ?? []
  return steps.map(reactivatePausedStepIfNeeded)
}

/** 续跑执行中：上游节点重新 pending/running 时，其余 paused 节点改为等待中 */
export function reactivateOtherPausedWorkflowNodes(
  steps: ProcessingStep[],
  activeNodeStepId: string,
): ProcessingStep[] {
  return steps.map(step => {
    if (step.subSteps?.length) {
      const subs = reactivateOtherPausedWorkflowNodes(step.subSteps, activeNodeStepId)
      if (subs !== step.subSteps) return { ...step, subSteps: subs }
    }
    if (!step.id.startsWith('node-') || step.id === activeNodeStepId) return step
    const lc = step.lifecycle
    if (lc !== 'paused') return step
    return toPendingResumeStep(step)
  })
}
