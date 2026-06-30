import type { ProcessingStep } from './processingSteps'
import { isHitlSummaryAwaiting, isToolStepId } from './hitlSteps'
import { stepHasHitlAwaiting } from './recoverySteps'

export type ReactRestartGate = {
  planningStarted: boolean
  resumeAtMs: number
}

export function createReactRestartGate(resumeAtMs: number): ReactRestartGate {
  return { planningStarted: false, resumeAtMs }
}

function isThinkPhase(phase: string | undefined): boolean {
  return phase === 'think' || !!phase?.startsWith('think')
}

function isFreshThinkStep(step: ProcessingStep, resumeAtMs: number): boolean {
  if (!isThinkPhase(step.phase)) return false
  const started = step.startedAt ?? step.ts
  if (started == null) return true
  return started >= resumeAtMs - 30_000
}

/** ReAct 续跑重规划：新 think 开始前丢弃暂停期 HITL / 旧 tool 步 */
export function applyReactRestartSseGate(
  gate: ReactRestartGate,
  event: { kind: 'step'; step: ProcessingStep } | { kind: 'confirmation' },
): ReactRestartGate {
  if (event.kind === 'confirmation') {
    return gate
  }
  const step = event.step
  if (step.id === 'intent') return gate
  const phase = step.phase ?? ''
  if (isThinkPhase(phase)
      && step.lifecycle !== 'paused'
      && step.lifecycle !== 'done'
      && (step.lifecycle === 'running' || step.lifecycle === 'pending')
      && isFreshThinkStep(step, gate.resumeAtMs)) {
    return { ...gate, planningStarted: true }
  }
  return gate
}

export function shouldDropReactRestartSse(
  gate: ReactRestartGate,
  event: { kind: 'step'; step: ProcessingStep } | { kind: 'confirmation' },
): boolean {
  if (event.kind === 'confirmation') {
    return !gate.planningStarted
  }
  const step = event.step
  if (step.id === 'intent') return false
  if (!gate.planningStarted) {
    // 仅丢弃暂停期旧 think；新 think 须放行，applyReactRestartSseGate 才能打开闸门
    if (isThinkPhase(step.phase)) {
      return !isFreshThinkStep(step, gate.resumeAtMs)
    }
    if (isToolStepId(step.id)) return true
    if (step.lifecycle === 'paused') return true
    if (step.lifecycle === 'done' && (isThinkPhase(step.phase) || isToolStepId(step.id))) return true
    if (isToolStepId(step.id) && (stepHasHitlAwaiting(step) || isHitlSummaryAwaiting(step))) return true
  }
  return false
}

/** 续跑重规划：think 步未开始前丢弃 reasoning 增量与正文 chunk */
export function shouldDropReactRestartStream(
  gate: ReactRestartGate,
  event: { kind: 'step_delta'; stepId: string } | { kind: 'content' } | { kind: 'reasoning' },
): boolean {
  if (gate.planningStarted) return false
  if (event.kind === 'step_delta') {
    return event.stepId === 'think' || event.stepId.startsWith('think-')
  }
  return event.kind === 'content' || event.kind === 'reasoning'
}
