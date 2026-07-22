import type { ProcessingStep } from './processingSteps'
import type { SessionState } from './chatSessionRegistry'

/** 测试辅助：深拷贝 steps；生产 bump 路径禁止使用（见 bumpAssistantMessage） */
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
  const walk = (list: ProcessingStep[]): ProcessingStep[] =>
    list.map(st => {
      if (st.id === nodeStepId) {
        const copy: ProcessingStep = {
          ...st,
          contentBlocks: st.contentBlocks?.map(b => ({ ...b })),
          subSteps: st.subSteps?.map(sub => ({ ...sub })),
        }
        mutate(copy)
        changed = true
        return copy
      }
      if (!st.subSteps?.length) return st
      const subs = walk(st.subSteps)
      if (subs === st.subSteps) return st
      changed = true
      return { ...st, subSteps: subs }
    })
  const next = walk(steps)
  return changed ? next : steps
}

/**
 * 触发时间线 UI 刷新。steps 已由 upsertStep/applyStepDelta 换新数组与变更项；
 * 禁止再深拷贝 detail（密集 write 时 O(n·|detail|) 会卡死主线程）。
 */
export function bumpAssistantMessage(session: SessionState): void {
  const idx = session.messages.length - 1
  const last = session.messages[idx]
  if (last?.role !== 'assistant') return
  session.streamRevision++
  session.messages = [
    ...session.messages.slice(0, idx),
    {
      ...last,
      pendingHitlConfirmation: undefined,
    },
  ]
}

/** 流式 step 合并刷新间隔；HITL 仍走 flush 立即刷 */
const BUMP_THROTTLE_MS = 80

const pendingBumpSessions = new Set<SessionState>()
let bumpTimer: ReturnType<typeof setTimeout> | null = null

export function scheduleAssistantMessageBump(session: SessionState): void {
  pendingBumpSessions.add(session)
  if (bumpTimer != null) return
  bumpTimer = setTimeout(() => {
    bumpTimer = null
    const batch = [...pendingBumpSessions]
    pendingBumpSessions.clear()
    for (const s of batch) {
      bumpAssistantMessage(s)
    }
  }, BUMP_THROTTLE_MS)
}

export function flushAssistantMessageBump(session?: SessionState): void {
  if (bumpTimer != null) {
    clearTimeout(bumpTimer)
    bumpTimer = null
  }
  if (session) {
    pendingBumpSessions.delete(session)
    bumpAssistantMessage(session)
    return
  }
  const batch = [...pendingBumpSessions]
  pendingBumpSessions.clear()
  for (const s of batch) {
    bumpAssistantMessage(s)
  }
}
