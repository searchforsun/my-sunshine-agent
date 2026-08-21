import type { ProcessingStep, StepMetadata } from './processingSteps'
import { isHitlSummaryAwaiting } from './hitlSteps'
import { isRecoveryAwaiting, stepHasHitlAwaiting } from './recoverySteps'

/** 续跑 SSE 重放：HITL/Recovery 节点勿被 pending 回退为「等待中」 */
export function shouldIgnoreResumeNodeStepReplay(steps: ProcessingStep[], incoming: ProcessingStep): boolean {
  if (!incoming.id.startsWith('node-')) return false
  const existing = steps.find(s => s.id === incoming.id)
  if (!existing) return false
  const hadAwaiting = stepHasHitlAwaiting(existing)
    || isHitlSummaryAwaiting(existing)
    || isRecoveryAwaiting(existing)
  if (!hadAwaiting) return false
  return incoming.lifecycle === 'pending'
    && (existing.lifecycle === 'running' || existing.lifecycle === 'paused')
}

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
        && (phase === 'think' || phase === 'agent' || phase === 'subagent' || phase === 'worker'
            || phase === 'generate'
            || phase === 'rag' || phase === 'intent' || phase === 'skill' || phase === 'tasks'
            || phase.startsWith('think') || phase.startsWith('tool')
            || next.id.startsWith('subagent-') || next.id.startsWith('worker-'))) {
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
  // subagent/worker 整轮停止时无单独 cancel SSE，乐观填「已取消」与后端取消终态一致
  const defaultAfter = step.phase === 'subagent' || step.phase === 'worker'
    || step.id.startsWith('subagent-') || step.id.startsWith('worker-')
    ? (step.summary?.after?.trim() || '已取消')
    : (step.summary?.after?.trim() || undefined)
  return {
    ...step,
    lifecycle: 'paused',
    summary: {
      before: step.summary?.before,
      active: undefined,
      after: defaultAfter,
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

/** HITL / Recovery 暂停续跑：恢复 running 并保留 metadata，供后端 checkpoint re-await */
function reactivateAwaitingPausedStep(step: ProcessingStep): ProcessingStep {
  const recovery = isRecoveryAwaiting(step)
  const defaultActive = recovery ? '发生错误' : '等待用户确认执行写操作'
  const active = step.summary?.active?.includes('暂停')
    ? defaultActive
    : (step.summary?.active?.trim() || defaultActive)
  return {
    ...step,
    lifecycle: 'running',
    summary: { ...step.summary, active, after: undefined },
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
  if (stepHasHitlAwaiting(next) || isHitlSummaryAwaiting(next) || isRecoveryAwaiting(next)) {
    return reactivateAwaitingPausedStep(next)
  }
  if (next.id.startsWith('node-')) return toPendingResumeStep(next)
  const phase = next.phase ?? ''
  if (phase === 'think' || phase === 'agent' || phase === 'generate'
      || phase.startsWith('think') || phase.startsWith('tool')) {
    return toPendingResumeStep(next)
  }
  return next
}

/** Plan workflow 节点 HITL 暂停续跑（与 ReAct reactivatePausedReactHitlSteps 对称） */
export function reactivatePausedPlanHitlNodes(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return steps ?? []
  return steps.map(step => {
    if (!step.id.startsWith('node-') || step.lifecycle !== 'paused') return step
    if (!stepHasHitlAwaiting(step) && !isHitlSummaryAwaiting(step)) return step
    return reactivateAwaitingPausedStep(step)
  })
}

/** ReAct 续跑：仅保留意图识别步 */
export function retainIntentStepsOnly(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return []
  return steps.filter(s => s.id === 'intent' || s.phase === 'intent')
}

/** 续跑开始：普通 paused 节点重置 pending；HITL/Recovery awaiting 保留 metadata 并恢复 running */
export function reactivatePausedStepsForResume(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return steps ?? []
  return steps.map(reactivatePausedStepIfNeeded)
}

/**
 * ReAct 续跑（reactRestart/checkpoint）重置：后端对复用 id 的步重放 running→done。
 * 暂停期被乐观标「已取消/已暂停」（paused）的步在前端是 cancel-terminal 硬终态，
 * resolveMergedLifecycle 会挡住后端重放的 running/done → 卡 running。恢复时把这些可续跑步
 * 重置为 pending 并清掉 after / 旧半截 reasoning，解除终态保护，让重放从空白干净落地。
 * 仅处理会被后端重放的步（subagent/think/tool/agent/generate/rag/tasks）；不动真 done 的历史步。
 */
export function resetStepsForReactResume(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return steps ?? []
  return steps.map(step => {
    let next = step
    if (step.subSteps?.length) {
      const subs = resetStepsForReactResume(step.subSteps)
      if (subs !== step.subSteps) next = { ...next, subSteps: subs }
    }
    if (next.lifecycle !== 'paused') {
      // 中断在 think 流式中途（running 残留，停止时未走 toPausedStep 的路径）：同 id 复用重推，
      // 旧半截 reasoning 会与新重放 step_delta 经 concatText 叠加覆盖 → 清。
      // done 终态的 think 后端不重放（或经 RESUME 保留旧 reasoning 续写），前端清空会闪烁 → 保留。
      if (next.lifecycle === 'running' && isThinkStep(next) && next.reasoning) {
        return { ...next, reasoning: '' }
      }
      return next
    }
    if (!isResumableReactStep(next)) return next
    return {
      ...next,
      lifecycle: 'pending',
      summary: next.summary?.before ? { before: next.summary.before } : undefined,
      reasoning: '',
      startedAt: undefined,
      endedAt: undefined,
      durationMs: undefined,
    }
  })
}

function isThinkStep(step: ProcessingStep): boolean {
  const phase = step.phase ?? ''
  return phase === 'think' || phase.startsWith('think')
    || step.id === 'think' || step.id.startsWith('think-')
}

/** 会被后端续跑重放（复用同 id）的 ReAct 步；intent 由 SSE 覆盖、不在此重置。
 *  subagent/worker 不在内：spawn/worker runId 每次新建，续跑不重放旧卡；
 *  全局取消后已标「已取消」的卡保持终态，不重置为等待中。 */
function isResumableReactStep(step: ProcessingStep): boolean {
  const phase = step.phase ?? ''
  return phase === 'think' || phase === 'agent' || phase === 'generate'
    || phase === 'rag' || phase === 'tasks'
    || phase.startsWith('think') || phase.startsWith('tool')
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
    if (stepHasHitlAwaiting(step) || isHitlSummaryAwaiting(step) || isRecoveryAwaiting(step)) {
      return step
    }
    return toPendingResumeStep(step)
  })
}
