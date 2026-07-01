import { computed, type ComputedRef, type Ref } from 'vue'
import type { ChatMessage } from '../api/chat'
import { ensurePlanTimelineSteps, hasPlanTimeline } from '../api/planHydrate'
import { sortSteps, hasActiveStep, type ProcessingStep } from '../api/processingSteps'
import { applySyncedPendingHitl, resolveHitlUiKey } from '../api/hitlSteps'

/** Chat 消息区时间线：steps 解析与 OperationStack 绑定 */
export function useChatTimelineView(messages: Ref<ChatMessage[]>, loading: Ref<boolean>) {
  function resolveTimelineContext(msg: ChatMessage): {
    steps: ProcessingStep[]
    pending: ChatMessage['pendingHitlConfirmation']
  } {
    const baseSteps = ensurePlanTimelineSteps(msg)
    if (!baseSteps.length) return { steps: [], pending: undefined }
    const synced = applySyncedPendingHitl(baseSteps, msg.pendingHitlConfirmation)
    return {
      steps: sortSteps(synced.steps),
      pending: synced.pending,
    }
  }

  function resolveTimelineSteps(msg: ChatMessage): ProcessingStep[] {
    return resolveTimelineContext(msg).steps
  }

  function resolveUserQuery(idx: number): string {
    for (let i = idx - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m?.role === 'user') {
        const text = m.content?.trim()
        if (text) return text
      }
    }
    return ''
  }

  function showTimeline(msg: ChatMessage, idx: number): boolean {
    if (hasPlanTimeline(msg)) return true
    return resolveTimelineSteps(msg).length > 0
  }

  function operationStackKey(msg: ChatMessage, idx: number): string {
    const ctx = resolveTimelineContext(msg)
    const hitl = resolveHitlUiKey(ctx.steps, ctx.pending)
    return `${msg.id ?? idx}-${hitl}`
  }

  function isTimelineLive(msg: ChatMessage, idx: number): boolean {
    return loading.value
      && idx === messages.value.length - 1
      && hasActiveStep(resolveTimelineSteps(msg))
  }

  const showStreamWaiting: ComputedRef<boolean> = computed(() => {
    if (!loading.value) return false
    const last = messages.value[messages.value.length - 1]
    if (last?.role !== 'assistant') return true
    if (last.content?.trim()) return false
    if (last.reasoning?.trim()) return false
    const idx = messages.value.length - 1
    if (hasActiveStep(resolveTimelineSteps(last))) return false
    return true
  })

  return {
    resolveTimelineContext,
    resolveTimelineSteps,
    resolveUserQuery,
    showTimeline,
    operationStackKey,
    isTimelineLive,
    showStreamWaiting,
  }
}
