/**
 * 多会话聊天管理 —— 每对话独立 DOM 容器 + StreamMarkdownRenderer
 * 切换只是显示/隐藏容器，不销毁、不中断后台渲染
 */
import { ref, reactive, computed } from 'vue'
import type { ChatMessage } from './chat'
import { applyStreamError, isAbortError, isPageUnloading } from './streamError'
import { apiHeaders } from '../stores/authStore'
import {
  saveActiveGeneration,
  loadActiveGeneration,
  clearActiveGeneration,
  updateLastSeq,
} from '../composables/useActiveGeneration'
import { BFF_STREAM_BASE } from './config'
import { parseSseEvent } from './sseParse'
import { parseSsePayload, type SseMeta } from './sseDispatch'
import { upsertStep, applyStepDelta, findRunningStepId } from './processingSteps'
import type { ProcessingStep } from './processingSteps'

const API_BASE = BFF_STREAM_BASE

export interface SessionState {
  id: string
  messages: ChatMessage[]
  loading: boolean
  abort: AbortController | null
  requestId: number
  containerEl: HTMLDivElement
  mounted: boolean
}

const sessions = new Map<string, SessionState>()

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => {
    for (const s of sessions.values()) {
      if (!s.loading) continue
      s.requestId++
      s.abort?.abort()
      s.loading = false
      // 刷新/关页：仅断开 SSE，保留 streaming + active generation 供 Track G 重连
    }
  })
}

export function appendChunk(existing: string, chunk: string): string {
  const maxOverlap = Math.min(existing.length, chunk.length, 64)
  for (let n = maxOverlap; n > 0; n--) {
    if (existing.endsWith(chunk.slice(0, n))) return existing + chunk.slice(n)
  }
  return existing + chunk
}

function getOrCreate(id: string): SessionState {
  if (!sessions.has(id)) {
    const el = document.createElement('div')
    el.className = 'msg-md'
    el.style.display = 'none'
    sessions.set(id, reactive({
      id,
      messages: [],
      loading: false,
      abort: null,
      requestId: 0,
      containerEl: el,
      mounted: false,
    }) as SessionState)
  }
  return sessions.get(id)!
}

export function useChatSessions(
  onChunk?: (sessionId: string, data: string) => void,
  onSessionEnd?: (id: string) => void,
  onProgress?: (sessionId: string) => void,
  onConversationMeta?: (sessionId: string, convId: string) => void,
) {
  const activeId = ref<string | null>(null)

  const activeSession = computed(() => {
    const id = activeId.value
    return id ? getOrCreate(id) : null
  })

  const messages = computed(() => (activeSession.value?.messages ?? []) as ChatMessage[])
  const loading = computed(() => activeSession.value?.loading ?? false)
  const activeContainer = computed(() => activeSession.value?.containerEl ?? null)

  function mountContainer(session: SessionState, parent: HTMLElement): void {
    if (session.mounted) return
    if (!session.containerEl.parentElement) {
      parent.appendChild(session.containerEl)
    }
    session.containerEl.style.display = ''
    session.mounted = true
  }

  function unmountContainer(session: SessionState): void {
    session.containerEl.style.display = 'none'
    session.mounted = false
  }

  function switchTo(id: string): void {
    if (activeId.value) {
      const old = sessions.get(activeId.value)
      if (old) unmountContainer(old)
    }
    activeId.value = id
  }

  function ensureActive(id: string): void {
    if (activeId.value !== id) switchTo(id)
  }

  async function consumeSseStream(
    s: SessionState,
    response: Response,
    thisRequestId: number,
    options: { resume?: boolean; onMeta?: (meta: SseMeta) => void } = {},
  ): Promise<void> {
    const reader = response.body?.getReader()
    if (!reader) throw new Error('No reader')

    const decoder = new TextDecoder()
    let buf = ''
    let streamConversationId = s.id

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buf += decoder.decode(value, { stream: true })
      const events = buf.split('\n\n')
      buf = events.pop() || ''

      for (const rawEvent of events) {
        const { id: eventId, payload: data } = parseSseEvent(rawEvent)
        if (data === null) continue

        let eventSeq: number | null = null
        if (eventId) {
          const n = parseInt(eventId, 10)
          if (!Number.isNaN(n)) eventSeq = n
        }

        const parsed = parseSsePayload(data)
        if (parsed.kind === 'ignore') continue

        if (parsed.kind === 'meta') {
          options.onMeta?.(parsed.meta)
          if (parsed.meta.type === 'conversation' && parsed.meta.id) {
            streamConversationId = parsed.meta.id
          }
          if (parsed.meta.type === 'generation' && parsed.meta.id && parsed.meta.messageId) {
            saveActiveGeneration({
              generationId: parsed.meta.id,
              messageId: parsed.meta.messageId,
              conversationId: streamConversationId,
              lastSeq: parsed.meta.seq ?? 0,
            })
            const last = s.messages[s.messages.length - 1]
            if (last?.role === 'assistant') {
              last.id = parsed.meta.messageId
            }
          }
          if (parsed.meta.type === 'message' && parsed.meta.id) {
            const last = s.messages[s.messages.length - 1]
            if (last?.role === 'assistant') {
              last.id = parsed.meta.id
              if (parsed.meta.status) last.status = parsed.meta.status as ChatMessage['status']
            }
          }
          if (parsed.meta.type === 'message' && parsed.meta.status === 'completed') {
            const last = s.messages[s.messages.length - 1]
            if (last?.role === 'assistant') last.status = 'completed'
          }
          if (parsed.meta.type === 'message' && parsed.meta.status === 'interrupted') {
            const last = s.messages[s.messages.length - 1]
            if (last?.role === 'assistant') last.status = 'interrupted'
          }
          if (parsed.meta.type === 'message' && parsed.meta.status === 'failed') {
            const last = s.messages[s.messages.length - 1]
            if (last?.role === 'assistant') last.status = 'failed'
          }
          continue
        }

        if (parsed.kind === 'reasoning') {
          if (eventSeq !== null) updateLastSeq(eventSeq)
          const lastMsg = s.messages[s.messages.length - 1]
          if (lastMsg?.role === 'assistant') {
            const prev = lastMsg.reasoning ?? ''
            lastMsg.reasoning = options.resume
              ? appendChunk(prev, parsed.text)
              : prev + parsed.text
            const runningId = findRunningStepId(lastMsg.steps ?? [])
            if (runningId) {
              lastMsg.steps = applyStepDelta(lastMsg.steps ?? [], {
                stepId: runningId,
                channel: 'reasoning',
                text: parsed.text,
              })
            }
          }
          onProgress?.(s.id)
          continue
        }

        if (parsed.kind === 'step') {
          if (eventSeq !== null) updateLastSeq(eventSeq)
          const lastMsg = s.messages[s.messages.length - 1]
          if (lastMsg?.role === 'assistant') {
            lastMsg.steps = upsertStep(lastMsg.steps ?? [], parsed.step)
          }
          onProgress?.(s.id)
          continue
        }

        if (parsed.kind === 'step_delta') {
          if (eventSeq !== null) updateLastSeq(eventSeq)
          const lastMsg = s.messages[s.messages.length - 1]
          if (lastMsg?.role === 'assistant') {
            let delta = parsed.delta
            if (delta.channel === 'reasoning' && delta.stepId === 'generate') {
                const steps = lastMsg.steps ?? []
                if (steps.some(st => st.id === 'think') || findRunningStepId(steps) === 'think') {
                  delta = { ...delta, stepId: 'think' }
                }
              }
            lastMsg.steps = applyStepDelta(lastMsg.steps ?? [], delta)
            // Agent 路径 reasoning 已在 agent 步骤内展示，勿双写 message.reasoning
            if (delta.channel === 'reasoning' && delta.stepId !== 'agent') {
              const prev = lastMsg.reasoning ?? ''
              lastMsg.reasoning = options.resume
                ? appendChunk(prev, delta.text)
                : prev + delta.text
            }
          }
          onProgress?.(s.id)
          continue
        }

        if (eventSeq !== null) updateLastSeq(eventSeq)

        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg?.role === 'assistant') {
          lastMsg.content = options.resume
            ? appendChunk(lastMsg.content, parsed.text)
            : lastMsg.content + parsed.text
          if (!lastMsg.status || lastMsg.status === 'interrupted') {
            lastMsg.status = 'streaming'
          }
        }

        onChunk?.(s.id, parsed.text)
        onProgress?.(s.id)
      }

      if (events.length > 0) await new Promise(r => setTimeout(r, 0))
    }
  }

  async function send(content: string, conversationId?: string | null): Promise<void> {
    const convId = conversationId ?? activeId.value
    if (!convId || !content.trim()) return

    ensureActive(convId)
    const s = activeSession.value
    if (!s || s.loading) return

    s.messages.push({ role: 'user', content })
    s.loading = true
    s.messages.push({ role: 'assistant', content: '', reasoning: '', steps: [], status: 'streaming' })

    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    const sessionId = s.id
    onProgress?.(sessionId)

    try {
      const body: Record<string, string> = { content, conversationId: convId }

      const response = await fetch(`${API_BASE}/api/chat/stream`, {
        method: 'POST',
        headers: apiHeaders(),
        body: JSON.stringify(body),
        signal: s.abort.signal,
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      await consumeSseStream(s, response, thisRequestId, {
        onMeta: (meta) => {
          if (meta.type === 'conversation' && meta.id) {
            onConversationMeta?.(sessionId, meta.id)
          }
        },
      })
    } catch (err: unknown) {
      applyStreamError(s.messages, err)
      if (isAbortError(err) || isPageUnloading()) {
        return
      }
      const last = s.messages[s.messages.length - 1]
      if (last?.role === 'assistant' && last.status === 'streaming') {
        last.status = 'interrupted'
      }
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        const last = s.messages[s.messages.length - 1]
        const aborted = s.abort?.signal.aborted ?? false
        if (last?.role === 'assistant' && last.status === 'streaming' && !aborted) {
          last.status = 'completed'
        }
        if (last?.role === 'assistant' && last.status === 'completed') {
          clearActiveGeneration()
        }
        onSessionEnd?.(sessionId)
      }
    }
  }

  async function resume(conversationId: string, resumeMessageId: string): Promise<void> {
    ensureActive(conversationId)
    const s = activeSession.value ?? getOrCreate(conversationId)
    if (s.loading) return

    const target = s.messages.find(m => m.id === resumeMessageId)
    if (!target || target.role !== 'assistant') return

    s.loading = true
    target.status = 'streaming'
    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    onProgress?.(conversationId)

    try {
      const response = await fetch(`${API_BASE}/api/chat/stream`, {
        method: 'POST',
        headers: apiHeaders(),
        body: JSON.stringify({ conversationId, resumeMessageId }),
        signal: s.abort.signal,
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      await consumeSseStream(s, response, thisRequestId, { resume: true })
    } catch (err: unknown) {
      applyStreamError(s.messages, err)
      if (target.status === 'streaming') target.status = 'interrupted'
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        if (target.status === 'streaming') target.status = 'completed'
        if (target.status === 'completed') clearActiveGeneration()
        onSessionEnd?.(conversationId)
      }
    }
  }

  async function reconnectStream(
    generationId: string,
    afterSeq: number,
    conversationId: string,
  ): Promise<void> {
    ensureActive(conversationId)
    const s = activeSession.value ?? getOrCreate(conversationId)
    if (s.loading) return

    const active = loadActiveGeneration()
    const messageId = active?.messageId

    let target = messageId
      ? s.messages.find(m => m.id === messageId && m.role === 'assistant')
      : s.messages[s.messages.length - 1]

    if (!target || target.role !== 'assistant') {
      target = { role: 'assistant', content: '', reasoning: '', steps: [], status: 'streaming', id: messageId }
      s.messages.push(target)
    }

    s.loading = true
    target.status = 'streaming'
    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    onProgress?.(conversationId)

    try {
      const response = await fetch(
        `${API_BASE}/api/chat/stream/${generationId}?afterSeq=${afterSeq}`,
        { headers: { ...apiHeaders(), Accept: 'text/event-stream' }, signal: s.abort.signal },
      )

      if (response.status === 410) {
        clearActiveGeneration()
        target.status = 'interrupted'
        return
      }

      if (!response.ok) throw new Error(`HTTP ${response.status}`)

      await consumeSseStream(s, response, thisRequestId, { resume: true })
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        if (target.status === 'streaming') target.status = 'interrupted'
        return
      }
      applyStreamError(s.messages, err)
      if (target.status === 'streaming') target.status = 'interrupted'
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        if (target.status === 'streaming') target.status = 'completed'
        if (target.status === 'completed') clearActiveGeneration()
        onSessionEnd?.(conversationId)
      }
    }
  }

  function stop(): void {
    const s = activeSession.value
    if (!s) return

    const active = loadActiveGeneration()
    if (active?.generationId) {
      fetch(`${API_BASE}/api/generations/${active.generationId}/cancel`, {
        method: 'POST',
        headers: apiHeaders(),
      }).catch(() => { /* fire and forget */ })
    }

    s.requestId++
    s.abort?.abort()
    s.loading = false
    const last = s.messages[s.messages.length - 1]
    if (last?.role === 'assistant' && (last.status === 'streaming' || !last.status)) {
      last.status = 'interrupted'
    }
  }

  function clearSession(): void {
    const s = activeSession.value
    if (!s) return
    s.messages = []
    s.containerEl.innerHTML = ''
  }

  function getMessages(id: string): ChatMessage[] {
    return getOrCreate(id).messages
  }

  function setMessages(id: string, msgs: ChatMessage[]): void {
    getOrCreate(id).messages = msgs
  }

  /** SSE 返回的 conversationId 与本地 sessionId 不一致时迁移消息 */
  function migrateSession(fromId: string, toId: string): void {
    if (!fromId || !toId || fromId === toId) return
    const from = sessions.get(fromId)
    if (!from) return
    const to = getOrCreate(toId)
    if (!to.messages.length && from.messages.length) {
      to.messages = from.messages
    }
    if (activeId.value === fromId) activeId.value = toId
    sessions.delete(fromId)
  }

  function destroySession(id: string): void {
    const s = sessions.get(id)
    if (s) {
      s.abort?.abort()
      s.containerEl.remove()
      sessions.delete(id)
    }
    if (activeId.value === id) activeId.value = null
  }

  return {
    messages, loading, activeContainer,
    switchTo, ensureActive, send, resume, reconnectStream, stop, clearSession,
    getMessages, setMessages, destroySession, migrateSession,
    mountContainer, unmountContainer, getOrCreate,
  }
}
