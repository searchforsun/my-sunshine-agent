import { nextTick, type Ref } from 'vue'
import type { ChatMessage } from '../api/chat'
import { resolveApiBase } from '../api/config'
import { apiHeaders } from '../stores/authStore'
import {
  normalizeRestoredInterleavedContent,
} from '../api/contentInterleave'
import { stepsHaveAwaitingHitl, getPendingHitlConfirmations } from '../api/hitlSteps'
import {
  clearActiveGeneration,
  type ActiveGeneration,
} from './useActiveGeneration'

interface ChatStoreLike {
  conversations: { id: string; messages?: ChatMessage[] }[]
  syncMessages: (id: string, msgs: ChatMessage[]) => void
  loadDetail: (id: string) => Promise<void>
}

/** 会话 hydrate、local/API 合并、Generation 自动续连 */
export function useChatSessionHydration(options: {
  chatStore: ChatStoreLike
  loading: Ref<boolean>
  getMessages: (id: string) => ChatMessage[]
  setMessages: (id: string, msgs: ChatMessage[]) => void
  reconnectStream: (generationId: string, afterSeq: number, convId: string) => Promise<void>
  captureSettledAssistantHtml: (content: string) => string
  resolveAssistantDisplayContent: (msg: ChatMessage) => string
  settledHtml: Ref<string>
  sessionSettledHtml: Map<string, string>
  ensureStreamRenderer: () => Promise<void>
  scrollToBottom: (force?: boolean) => void
  enhanceAllStaticMarkdown: () => void
}) {
  const {
    chatStore,
    loading,
    getMessages,
    setMessages,
    reconnectStream,
    captureSettledAssistantHtml,
    resolveAssistantDisplayContent,
    settledHtml,
    sessionSettledHtml,
    ensureStreamRenderer,
    scrollToBottom,
    enhanceAllStaticMarkdown,
  } = options

  const persistTimers = new Map<string, ReturnType<typeof setTimeout>>()

  function schedulePersist(sessionId: string) {
    const prev = persistTimers.get(sessionId)
    if (prev) clearTimeout(prev)
    persistTimers.set(sessionId, setTimeout(() => {
      persistTimers.delete(sessionId)
      chatStore.syncMessages(sessionId, getMessages(sessionId))
    }, 400))
  }

  function flushPersist(sessionId?: string | null) {
    if (sessionId) {
      const t = persistTimers.get(sessionId)
      if (t) clearTimeout(t)
      persistTimers.delete(sessionId)
      chatStore.syncMessages(sessionId, getMessages(sessionId))
      return
    }
    for (const [id, t] of persistTimers) {
      clearTimeout(t)
      chatStore.syncMessages(id, getMessages(id))
    }
    persistTimers.clear()
  }

  function pickLongerContent(a: string, b: string): string {
    if (!b.trim()) return a
    if (!a.trim()) return b
    return b.length >= a.length ? b : a
  }

  function pickPreferredAssistantStatus(
    api?: ChatMessage['status'],
    local?: ChatMessage['status'],
  ): ChatMessage['status'] | undefined {
    const rank = (s?: ChatMessage['status']) => {
      if (s === 'completed') return 4
      if (s === 'streaming') return 3
      if (s === 'interrupted') return 2
      if (s === 'failed') return 1
      return 0
    }
    if (rank(local) >= rank(api)) return local ?? api
    return api ?? local
  }

  function mergeAssistantTail(restoredLast: ChatMessage, localLast: ChatMessage): void {
    restoredLast.content = pickLongerContent(restoredLast.content ?? '', localLast.content ?? '')
    const localReasoning = localLast.reasoning?.trim() ?? ''
    const restoredReasoning = restoredLast.reasoning?.trim() ?? ''
    if (localReasoning.length >= restoredReasoning.length) {
      restoredLast.reasoning = localLast.reasoning
    }
    const localSteps = localLast.steps?.length ?? 0
    const restoredSteps = restoredLast.steps?.length ?? 0
    const localIntentOnly = localSteps === 1 && localLast.steps?.[0]?.id === 'intent'
    const localHasHitl = !localIntentOnly && (
      stepsHaveAwaitingHitl(localLast.steps)
      || getPendingHitlConfirmations(localLast).length > 0
    )
    if (localIntentOnly || localSteps >= restoredSteps || localHasHitl) {
      restoredLast.steps = localLast.steps
    }
    if (localIntentOnly) {
      restoredLast.content = localLast.content ?? ''
      restoredLast.reasoning = localLast.reasoning ?? ''
      restoredLast.contentBlocks = localLast.contentBlocks
      restoredLast.pendingHitlConfirmations = undefined
    } else if (getPendingHitlConfirmations(localLast).length && !localIntentOnly) {
      restoredLast.pendingHitlConfirmations = getPendingHitlConfirmations(localLast)
    }
    if (localLast.contentBlocks?.length) {
      const localJoined = localLast.contentBlocks.map(b => b.text).join('')
      const restoredJoined = restoredLast.contentBlocks?.map(b => b.text).join('') ?? ''
      if (localJoined.length >= restoredJoined.length) {
        restoredLast.contentBlocks = localLast.contentBlocks
      }
    }
    restoredLast.status = pickPreferredAssistantStatus(restoredLast.status, localLast.status)
    if (localLast.timelineStartedAt != null) {
      restoredLast.timelineStartedAt = localLast.timelineStartedAt
    }
    if (localLast.timelineEndedAt != null
      && (restoredLast.timelineEndedAt == null || localLast.timelineEndedAt > restoredLast.timelineEndedAt)) {
      restoredLast.timelineEndedAt = localLast.timelineEndedAt
    }
    if (localLast.streamError && !restoredLast.streamError) {
      restoredLast.streamError = localLast.streamError
    }
    normalizeRestoredInterleavedContent(restoredLast)
  }

  function markAssistantFailed(convId: string, messageId?: string) {
    const msgs = getMessages(convId)
    const target = messageId
      ? msgs.find(m => m.id === messageId && m.role === 'assistant')
      : msgs[msgs.length - 1]
    if (target?.role === 'assistant' && target.status !== 'completed') {
      target.status = 'failed'
    }
  }

  function markAssistantInterrupted(convId: string, messageId?: string) {
    const msgs = getMessages(convId)
    const target = messageId
      ? msgs.find(m => m.id === messageId && m.role === 'assistant')
      : msgs[msgs.length - 1]
    if (target?.role === 'assistant' && target.status !== 'completed') {
      target.status = 'interrupted'
    }
  }

  function syncSessionToStore(cid: string) {
    chatStore.syncMessages(cid, getMessages(cid))
    const lastAssistant = [...getMessages(cid)].reverse().find(m => m.role === 'assistant')
    if (lastAssistant?.content?.trim() && !loading.value) {
      settledHtml.value = captureSettledAssistantHtml(resolveAssistantDisplayContent(lastAssistant))
      sessionSettledHtml.set(cid, settledHtml.value)
    } else if (!loading.value) {
      settledHtml.value = sessionSettledHtml.get(cid) ?? ''
    }
  }

  async function hydrateSessionFromStore(cid: string, opts?: { skipApiLoad?: boolean }) {
    const skipApi = opts?.skipApiLoad ?? loading.value
    if (!skipApi) {
      await chatStore.loadDetail(cid)
    }
    const restored = chatStore.conversations.find(c => c.id === cid)?.messages ?? []
    const local = getMessages(cid)
    if (local.length && restored.length) {
      const localLast = local[local.length - 1]
      const restoredLast = restored[restored.length - 1]
      if (localLast?.role === 'assistant' && restoredLast?.role === 'assistant') {
        mergeAssistantTail(restoredLast, localLast)
      }
    }
    if (!restored.length) {
      settledHtml.value = ''
      return
    }
    setMessages(cid, [...restored])
    for (const m of restored) {
      if (m.role === 'assistant') normalizeRestoredInterleavedContent(m)
    }
    const lastAssistant = [...restored].reverse().find(m => m.role === 'assistant')
    if (lastAssistant?.content?.trim() && !loading.value) {
      settledHtml.value = captureSettledAssistantHtml(resolveAssistantDisplayContent(lastAssistant))
      sessionSettledHtml.set(cid, settledHtml.value)
    } else if (!loading.value) {
      settledHtml.value = sessionSettledHtml.get(cid) ?? ''
    }
    await nextTick()
    enhanceAllStaticMarkdown()
    scrollToBottom(false)
  }

  async function tryAutoReconnect(cid: string, active: ActiveGeneration) {
    try {
      const resp = await fetch(`${resolveApiBase()}/api/generations/${active.generationId}`, {
        headers: apiHeaders(),
      })
      if (resp.status === 410 || resp.status === 404) {
        clearActiveGeneration()
        markAssistantInterrupted(cid, active.messageId)
        await hydrateSessionFromStore(cid)
        return
      }
      if (!resp.ok) {
        await hydrateSessionFromStore(cid)
        return
      }
      const status = await resp.json() as { status: string; lastSeq: number }
      if (status.status === 'INTERRUPTED') {
        clearActiveGeneration()
        markAssistantInterrupted(cid, active.messageId)
        await hydrateSessionFromStore(cid)
        return
      }
      if (status.status === 'FAILED') {
        clearActiveGeneration()
        markAssistantFailed(cid, active.messageId)
        await hydrateSessionFromStore(cid)
        return
      }
      if (status.status === 'COMPLETED') {
        clearActiveGeneration()
        await hydrateSessionFromStore(cid)
        return
      }
      if (status.status === 'RUNNING') {
        const msgs = getMessages(cid)
        const tail = active.messageId
          ? msgs.find(m => m.id === active.messageId && m.role === 'assistant')
          : msgs[msgs.length - 1]
        if (tail?.role === 'assistant') {
          normalizeRestoredInterleavedContent(tail)
        }
        // 续连必须从「前端已消费到的 seq」之后重放，不能因 isContentFullyInterleaved 就跳到
        // 后端最新 seq：流式中 contentBlocks 与 content 一致并不代表前端已收到后端最新 chunk，
        // 跳变会跳过中间 seq 的 chunk，导致刷新后续连丢内容、loading 卡死、重新生成无反应。
        let afterSeq = active.lastSeq
        if (afterSeq <= 0 && (status.lastSeq ?? 0) > 0 && tail?.content?.trim()) {
          afterSeq = status.lastSeq
        }
        await nextTick()
        const reconnectPromise = reconnectStream(active.generationId, afterSeq, cid)
        await nextTick()
        await ensureStreamRenderer()
        await reconnectPromise
        // 续传异常结束（网络失败 / 后端已终态导致 SSE 中断）：重新拉取 API + 本地缓存，
        // 后端 commitFinal 可能已落库已输出的步骤/正文，避免停留在空白或失败态消息上。
        // 注意：不清除 active generation 锚点——后端可能仍在 RUNNING，只是续连流被网络/导航
        // 意外中断；清掉锚点会导致下次刷新不再续连（「多刷新两次就断流」的根因）。
        // 后端若已终态，hydrate 后消息会变为对应终态；若仍在跑，保留锚点供下次刷新续连。
        const tailAfter = active.messageId
          ? msgs.find(m => m.id === active.messageId && m.role === 'assistant')
          : msgs[msgs.length - 1]
        if (
          tailAfter?.role === 'assistant' &&
          tailAfter.status !== 'completed' &&
          tailAfter.status !== 'streaming'
        ) {
          await hydrateSessionFromStore(cid)
          // hydrate 优先取 API 的 streaming 状态（后端仍在跑），但此刻本地已无活跃续连流，
          // 保持 interrupted 让「重新生成」入口可用；锚点保留，下次刷新仍会续连。
          const after = active.messageId
            ? getMessages(cid).find(m => m.id === active.messageId && m.role === 'assistant')
            : getMessages(cid)[getMessages(cid).length - 1]
          if (after?.role === 'assistant' && after.status === 'streaming') {
            after.status = 'interrupted'
          }
          return
        }
        syncSessionToStore(cid)
      }
    } catch (e) {
      console.error('[ChatView] auto reconnect failed', e)
      // 网络/导航等瞬时异常：后端 generation 很可能仍在 RUNNING，绝不能清除 active 锚点——
      // 清掉后后续刷新将不再续连（「多刷新两次就中断流式输出」的直接根因：快速刷新时
      // 状态查询/续连请求被导航中断，抛 TypeError: Failed to fetch 落入本 catch）。
      // 保留锚点供下次刷新重试；同时把停在 streaming 的尾部 assistant 标 interrupted，
      // 避免 UI 停在假流式状态且无任何「重新生成」恢复入口。
      await hydrateSessionFromStore(cid)
      const after = active.messageId
        ? getMessages(cid).find(m => m.id === active.messageId && m.role === 'assistant')
        : getMessages(cid)[getMessages(cid).length - 1]
      if (after?.role === 'assistant' && after.status === 'streaming') {
        after.status = 'interrupted'
      }
    }
  }

  function flushAllOnPageHide() {
    flushPersist()
    for (const conv of chatStore.conversations) {
      const msgs = getMessages(conv.id)
      if (msgs.length) chatStore.syncMessages(conv.id, msgs)
    }
  }

  return {
    schedulePersist,
    flushPersist,
    hydrateSessionFromStore,
    tryAutoReconnect,
    syncSessionToStore,
    flushAllOnPageHide,
  }
}
