import { computed, onUnmounted, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { ChatMessage } from '../api/chat'
import { ensurePlanTimelineSteps, hasPlanTimeline } from '../api/planHydrate'
import { sortSteps, hasActiveStep, type ProcessingStep } from '../api/processingSteps'
import { applySyncedPendingHitl, resolveHitlUiKey, getPendingHitlConfirmations, type HitlConfirmationPayload } from '../api/hitlSteps'

/** 底部三点：流式空档持续多久才显示（与 OperationStack 空档三点一致） */
const STREAM_IDLE_DOTS_MS = 2000

/** Chat 消息区时间线：steps 解析与 OperationStack 绑定 */
export function useChatTimelineView(messages: Ref<ChatMessage[]>, loading: Ref<boolean>) {
  /**
   * resolveTimelineContext 结果缓存：流式 bump 每 80ms 替换 messages 数组触发整页重渲染，
   * 若对每条历史消息都重算 ensurePlanTimelineSteps + applySyncedPendingHitl + sortSteps（全新建数组），
   * 轮次越多每次流式 chunk 成本线性上涨。依赖引用未变时直接复用结果，
   * 保证历史消息 OperationStack 的 props.steps 引用稳定，Vue 跳过子组件更新。
   */
  const timelineContextCache = new WeakMap<ChatMessage, {
    stepsRef: ProcessingStep[] | undefined
    planId: string | undefined
    pendingRef: HitlConfirmationPayload[] | undefined
    result: { steps: ProcessingStep[]; pending: HitlConfirmationPayload[] }
  }>()

  function resolveTimelineContext(msg: ChatMessage): {
    steps: ProcessingStep[]
    pending: HitlConfirmationPayload[]
  } {
    const cached = timelineContextCache.get(msg)
    if (cached
      && cached.stepsRef === msg.steps
      && cached.planId === msg.executionPlanId
      && cached.pendingRef === msg.pendingHitlConfirmations) {
      return cached.result
    }
    const baseSteps = ensurePlanTimelineSteps(msg)
    let result: { steps: ProcessingStep[]; pending: HitlConfirmationPayload[] }
    if (!baseSteps.length) {
      result = { steps: [], pending: [] }
    } else {
      const synced = applySyncedPendingHitl(baseSteps, getPendingHitlConfirmations(msg))
      result = { steps: sortSteps(synced.steps), pending: synced.pending ?? [] }
    }
    timelineContextCache.set(msg, {
      stepsRef: msg.steps,
      planId: msg.executionPlanId,
      pendingRef: msg.pendingHitlConfirmations,
      result,
    })
    return result
  }

  function resolveTimelineSteps(msg: ChatMessage): ProcessingStep[] {
    return resolveTimelineContext(msg).steps
  }

  /** 该 assistant 消息对应最近的用户问题，按消息 id 缓存，避免整页重渲染时反复前向扫描 */
  const userQueryCache = new Map<string, string>()

  function resolveUserQuery(idx: number): string {
    const msg = messages.value[idx]
    if (!msg || msg.role !== 'assistant') return ''
    if (msg.id && userQueryCache.has(msg.id)) return userQueryCache.get(msg.id)!
    let q = ''
    for (let i = idx - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m?.role === 'user') {
        const text = m.content?.trim()
        if (text) {
          q = text
          break
        }
      }
    }
    if (msg.id) userQueryCache.set(msg.id, q)
    return q
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
    if (!loading.value || idx !== messages.value.length - 1) return false
    return hasActiveStep(resolveTimelineSteps(msg))
  }

  /** 无正文/时间线时的即时空档条件（尚未施加 2s 静默）。
   * 仅覆盖「首步尚未到达」；一旦有 steps，空档三点由 OperationStack 独占，
   * 避免与折叠/展开态 op-answer-dots 叠成两行。 */
  function isStreamWaitingGap(): boolean {
    if (!loading.value) return false
    const last = messages.value[messages.value.length - 1]
    if (last?.role !== 'assistant') return true
    if (last.content?.trim()) return false
    if (last.reasoning?.trim()) return false
    if (last.contentBlocks?.some(b => !!b.text?.trim())) return false
    if (resolveTimelineSteps(last).length > 0) return false
    return true
  }

  const streamWaitingArmed = ref(false)
  let streamWaitingTimer: ReturnType<typeof setTimeout> | undefined

  watch(
    () => isStreamWaitingGap(),
    (inGap) => {
      if (streamWaitingTimer != null) {
        clearTimeout(streamWaitingTimer)
        streamWaitingTimer = undefined
      }
      if (!inGap) {
        streamWaitingArmed.value = false
        return
      }
      // 进入空档后须静默满 2s 才露三点；期间若有流式/步骤则重新计时
      streamWaitingArmed.value = false
      streamWaitingTimer = setTimeout(() => {
        streamWaitingTimer = undefined
        if (isStreamWaitingGap()) streamWaitingArmed.value = true
      }, STREAM_IDLE_DOTS_MS)
    },
    { immediate: true },
  )

  onUnmounted(() => {
    if (streamWaitingTimer != null) clearTimeout(streamWaitingTimer)
  })

  const showStreamWaiting: ComputedRef<boolean> = computed(() => streamWaitingArmed.value)

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
