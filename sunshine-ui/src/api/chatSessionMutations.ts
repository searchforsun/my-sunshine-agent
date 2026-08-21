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
  const walk = (list: ProcessingStep[]): ProcessingStep[] => {
    let listChanged = false
    const next = list.map(st => {
      if (st.id === nodeStepId) {
        // 仅复制正文载体：subSteps 子步骤对象保持原引用，避免正文每 token 增量
        // 重建子步骤数组，导致抽屉时间线（OperationStack）全量 patch 所有子卡片。
        const copy: ProcessingStep = {
          ...st,
          contentBlocks: st.contentBlocks?.map(b => ({ ...b })),
        }
        mutate(copy)
        changed = true
        listChanged = true
        return copy
      }
      if (!st.subSteps?.length) return st
      const subs = walk(st.subSteps)
      if (subs === st.subSteps) return st
      changed = true
      listChanged = true
      return { ...st, subSteps: subs }
    })
    return listChanged ? next : list
  }
  const next = walk(steps)
  return changed ? next : steps
}

/**
 * 触发时间线 UI 刷新。
 * 流式中 steps/content/reasoning 已通过对象属性原位修改更新，
 * 此处通过原位浅拷贝替换最后一条消息对象，触发 Vue 对该 v-for 项的精准重渲染，
 * 避免重建整个 messages 数组导致历史消息全量 diff。
 */
export function bumpAssistantMessage(session: SessionState): void {
  const idx = session.messages.length - 1
  const last = session.messages[idx]
  if (last?.role !== 'assistant') return
  session.streamRevision++
  session.messages[idx] = { ...last }
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
