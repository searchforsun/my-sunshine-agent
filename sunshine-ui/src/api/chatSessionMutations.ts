import type { ProcessingStep } from './processingSteps'
import type { SessionState } from './chatSessionRegistry'

export function cloneStepsForReactive(steps: ProcessingStep[]): ProcessingStep[] {
  return steps.map(step => ({
    ...step,
    summary: step.summary ? { ...step.summary } : step.summary,
    metadata: step.metadata ? { ...step.metadata } : step.metadata,
    contentBlocks: step.contentBlocks?.map(b => ({ ...b })),
    subSteps: step.subSteps?.map(sub => ({
      ...sub,
      summary: sub.summary ? { ...sub.summary } : sub.summary,
      metadata: sub.metadata ? { ...sub.metadata } : sub.metadata,
    })),
  }))
}

export function updateNodeStepContent(
  steps: ProcessingStep[],
  nodeStepId: string,
  mutate: (step: ProcessingStep) => void,
): ProcessingStep[] {
  let changed = false
  const next = steps.map(st => {
    if (st.id !== nodeStepId) return st
    const copy: ProcessingStep = {
      ...st,
      contentBlocks: st.contentBlocks?.map(b => ({ ...b })),
    }
    mutate(copy)
    changed = true
    return copy
  })
  return changed ? next : steps
}

export function bumpAssistantMessage(session: SessionState): void {
  const idx = session.messages.length - 1
  const last = session.messages[idx]
  if (last?.role !== 'assistant') return
  session.streamRevision++
  session.messages = [
    ...session.messages.slice(0, idx),
    {
      ...last,
      steps: last.steps?.length ? cloneStepsForReactive(last.steps) : last.steps,
      contentBlocks: last.contentBlocks?.map(b => ({ ...b })),
      pendingHitlConfirmation: last.pendingHitlConfirmation
        ? { ...last.pendingHitlConfirmation }
        : last.pendingHitlConfirmation,
    },
  ]
}
