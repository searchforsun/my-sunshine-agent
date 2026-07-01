import { nextTick, type Ref } from 'vue'
import type { ChatMessage } from '../api/chat'
import { resolveApiBase } from '../api/config'
import { apiHeaders } from '../stores/authStore'
import {
  isContentFullyInterleaved,
  normalizeRestoredInterleavedContent,
} from '../api/contentInterleave'
import { stepsHaveAwaitingHitl } from '../api/hitlSteps'
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
      || !!localLast.pendingHitlConfirmation
    )
    if (localIntentOnly || localSteps >= restoredSteps || localHasHitl) {
      restoredLast.steps = localLast.steps
    }
    if (localIntentOnly) {
      restoredLast.content = localLast.content ?? ''
      restoredLast.reasoning = localLast.reasoning ?? ''
      restoredLast.contentBlocks = localLast.contentBlocks
      restoredLast.pendingHitlConfirmation = undefined
    } else if (localLast.pendingHitlConfirmation && !localIntentOnly) {
      restoredLast.pendingHitlConfirmation = localLast.pendingHitlConfirmation
    }
    if (localLast.contentBlocks?.length) {
      const localJoined = localLast.contentBlocks.map(b => b.text).join('')
      const restoredJoined = restoredLast.contentBlocks?.map(b => b.text).join('') ?? ''
      if (localJoined.length >= restoredJoined.length) {
        restoredLast.contentBlocks = localLast.contentBlocks
      }
    }
    restoredLast.status = pickPreferredAssistantStatus(restoredLast.status, localLast.status)
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
    scrollToBottom()
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
        let afterSeq = active.lastSeq
        if (tail?.role === 'assistant' && isContentFullyInterleaved(tail)) {
          afterSeq = Math.max(afterSeq, status.lastSeq ?? 0)
        } else if (afterSeq <= 0 && (status.lastSeq ?? 0) > 0 && tail?.content?.trim()) {
          afterSeq = status.lastSeq
        }
        await nextTick()
        const reconnectPromise = reconnectStream(active.generationId, afterSeq, cid)
        await nextTick()
        await ensureStreamRenderer()
        await reconnectPromise
        syncSessionToStore(cid)
      }
    } catch (e) {
      console.error('[ChatView] auto reconnect failed', e)
      clearActiveGeneration()
      await hydrateSessionFromStore(cid)
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
