/**
 * 多会话聊天管理 —— 每对话独立 DOM 容器 + StreamMarkdownRenderer
 * 切换只是显示/隐藏容器，不销毁、不中断后台渲染
 */
import { ref, reactive, computed } from 'vue'
import type { ChatMessage } from './chat'
import {
  applyStreamError,
  applyStreamErrorFromText,
  hydrateStreamError,
  isAbortError,
  isPageUnloading,
} from './streamError'
import { apiHeaders } from '../stores/authStore'
import {
  saveActiveGeneration,
  loadActiveGeneration,
  clearActiveGeneration,
  updateLastSeq,
} from '../composables/useActiveGeneration'
import { BFF_STREAM_BASE } from './config'
import { ApiError, throwIfHttpError } from './apiError'
import { parseSseEvent } from './sseParse'
import { parseSsePayload, type SseMeta } from './sseDispatch'
import { mergeHitlIntoRunningToolStep, applyHitlDecision as applyHitlDecisionToSteps, relocateAgentNodeHitl } from './hitlSteps'
import { applyRecoveryDecision as applyRecoveryDecisionToSteps } from './recoverySteps'
import { upsertStep, applyStepDelta, findRunningStepId, isWorkflowNodeStepId, pauseRunningWorkflowNodes } from './processingSteps'
import type { ProcessingStep } from './processingSteps'
import type { ExecutionPreference } from './executionModes'

const API_BASE = BFF_STREAM_BASE

export interface SendOptions {
  executionPreference?: ExecutionPreference
  workflowId?: string | null
  /** 前端解析到的 catalog skill，后端 L0 优先绑定 */
  skillId?: string
}

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
    if (!reader) throw new ApiError('服务响应异常，请稍后重试', { kind: 'parse' })

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
            const convId = streamConversationId ?? s.id
            if (convId) {
              saveActiveGeneration({
                generationId: parsed.meta.id,
                messageId: parsed.meta.messageId,
                conversationId: convId,
                lastSeq: parsed.meta.seq ?? 0,
              })
            }
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
            if (last?.role === 'assistant') {
              last.status = 'failed'
              hydrateStreamError(last)
              if (!last.streamError) {
                last.streamError = '可点击下方继续生成重试'
              }
            }
          }
          continue
        }

        if (parsed.kind === 'error') {
          if (eventSeq !== null) updateLastSeq(eventSeq)
          const lastMsg = s.messages[s.messages.length - 1]
          if (lastMsg?.role === 'assistant') {
            applyStreamErrorFromText(lastMsg, parsed.text)
          }
          onProgress?.(s.id)
          continue
        }

        if (parsed.kind === 'reasoning') {
          if (eventSeq !== null) updateLastSeq(eventSeq)
          const lastMsg = s.messages[s.messages.length - 1]
          if (lastMsg?.role === 'assistant') {
            const runningId = findRunningStepId(lastMsg.steps ?? [])
            if (runningId) {
              lastMsg.steps = applyStepDelta(lastMsg.steps ?? [], {
                stepId: runningId,
                channel: 'reasoning',
                text: parsed.text,
              })
            }
            // workflow node-* 的 reasoning 只挂在步骤上，不写入 message.reasoning
            if (!isWorkflowNodeStepId(runningId)) {
              const prev = lastMsg.reasoning ?? ''
              lastMsg.reasoning = options.resume
                ? appendChunk(prev, parsed.text)
                : prev + parsed.text
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
            // ReAct think / workflow node-*：reasoning 仅在 steps 内展示
            const isThinkStep = delta.stepId === 'think' || delta.stepId.startsWith('think-')
            const isNodeStep = isWorkflowNodeStepId(delta.stepId)
            if (delta.channel === 'reasoning' && delta.stepId !== 'agent' && !isThinkStep && !isNodeStep) {
              const prev = lastMsg.reasoning ?? ''
              lastMsg.reasoning = options.resume
                ? appendChunk(prev, delta.text)
                : prev + delta.text
            }
          }
          onProgress?.(s.id)
          continue
        }

        if (parsed.kind === 'confirmation') {
          if (eventSeq !== null) updateLastSeq(eventSeq)
          const lastMsg = s.messages[s.messages.length - 1]
          if (lastMsg?.role === 'assistant') {
            lastMsg.steps = mergeHitlIntoRunningToolStep(
              lastMsg.steps ?? [],
              parsed.confirmation,
            ).map(s => (s.id.startsWith('node-') ? relocateAgentNodeHitl(s) : s))
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

  async function send(content: string, conversationId?: string | null, options?: SendOptions): Promise<void> {
    const convId = conversationId ?? activeId.value
    if (!convId || !content.trim()) return

    ensureActive(convId)
    const s = activeSession.value
    if (!s || s.loading) return

    const pref = options?.executionPreference ?? 'auto'
    s.messages.push({ role: 'user', content, executionPreference: pref })
    s.loading = true
    s.messages.push({ role: 'assistant', content: '', reasoning: '', steps: [], status: 'streaming' })

    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    const sessionId = s.id
    onProgress?.(sessionId)

    try {
      const body: Record<string, string> = { content, conversationId: convId }
      if (pref !== 'auto') {
        body.executionPreference = pref
      }
      if (options?.workflowId) {
        body.workflowId = options.workflowId
      }
      if (options?.skillId) {
        body.skillId = options.skillId
      }

      const response = await fetch(`${API_BASE}/api/chat/stream`, {
        method: 'POST',
        headers: apiHeaders(),
        body: JSON.stringify(body),
        signal: s.abort.signal,
      })

      if (!response.ok) await throwIfHttpError(response)

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
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        const last = s.messages[s.messages.length - 1]
        const aborted = s.abort?.signal.aborted ?? false
        if (last?.role === 'assistant' && last.status === 'streaming' && !aborted) {
          hydrateStreamError(last)
          last.status = last.streamError ? 'failed' : 'completed'
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

    const planWorkflowResume = target.steps?.some(
      step => step.id.startsWith('node-') && (step.lifecycle === 'paused' || step.status === 'paused'),
    )
    if (planWorkflowResume) {
      target.content = ''
      target.reasoning = ''
    }

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

      if (!response.ok) await throwIfHttpError(response)

      await consumeSseStream(s, response, thisRequestId, { resume: true })
    } catch (err: unknown) {
      applyStreamError(s.messages, err)
      if (!isAbortError(err) && !isPageUnloading() && target.status === 'streaming') {
        target.status = target.streamError ? 'failed' : 'interrupted'
      }
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        if (target.status === 'streaming') {
          hydrateStreamError(target)
          target.status = target.streamError ? 'failed' : 'interrupted'
        }
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

      if (!response.ok) await throwIfHttpError(response)

      await consumeSseStream(s, response, thisRequestId, { resume: true })
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        if (target.status === 'streaming') target.status = 'interrupted'
        return
      }
      applyStreamError(s.messages, err)
      if (target.status === 'streaming') {
        target.status = target.streamError ? 'failed' : 'interrupted'
      }
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        if (target.status === 'streaming') target.status = 'completed'
        if (target.status === 'completed') clearActiveGeneration()
        onSessionEnd?.(conversationId)
      }
    }
  }

  async function stop(): Promise<void> {
    const s = activeSession.value
    if (!s) return

    const active = loadActiveGeneration()
    if (active?.generationId) {
      try {
        await fetch(`${API_BASE}/api/generations/${active.generationId}/cancel`, {
          method: 'POST',
          headers: apiHeaders(),
        })
      } catch { /* fire and forget */ }
    }

    s.requestId++
    const last = s.messages[s.messages.length - 1]
    if (last?.role === 'assistant') {
      if (last.steps?.length) {
        last.steps = pauseRunningWorkflowNodes(last.steps)
      }
      if (last.status === 'streaming' || !last.status) {
        last.status = 'interrupted'
      }
    }
    s.abort?.abort()
    s.loading = false
    onProgress?.(s.id)
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

  function applyHitlDecision(token: string, approved: boolean): void {
    const s = activeSession.value
    if (!s) return
    for (let i = s.messages.length - 1; i >= 0; i--) {
      const msg = s.messages[i]
      if (msg.role !== 'assistant' || !msg.steps?.length) continue
      const next = applyHitlDecisionToSteps(msg.steps, token, approved)
      if (next !== msg.steps) {
        msg.steps = next
        onProgress?.(s.id)
        return
      }
    }
  }

  function applyRecoveryDecision(token: string, action: 'retry' | 'terminate' | 'skip'): void {
    const s = activeSession.value
    if (!s) return
    for (let i = s.messages.length - 1; i >= 0; i--) {
      const msg = s.messages[i]
      if (msg.role !== 'assistant' || !msg.steps?.length) continue
      const next = applyRecoveryDecisionToSteps(msg.steps, token, action)
      if (next !== msg.steps) {
        msg.steps = next
        onProgress?.(s.id)
        return
      }
    }
  }

  return {
    messages, loading, activeContainer,
    switchTo, ensureActive, send, resume, reconnectStream, stop, clearSession,
    getMessages, setMessages, destroySession, migrateSession,
    mountContainer, unmountContainer, getOrCreate, applyHitlDecision, applyRecoveryDecision,
  }
}
