/**
 * 多会话聊天管理 —— 每对话独立 DOM 容器 + StreamMarkdownRenderer
 * 切换只是显示/隐藏容器，不销毁、不中断后台渲染
 */
import { ref, reactive, computed, type ComputedRef } from 'vue'
import type { ChatMessage } from './chat'

const API_BASE = 'http://localhost:8001'

export interface SessionState {
  id: string
  messages: ChatMessage[]
  loading: boolean
  abort: AbortController | null
  requestId: number
  /** 独立 DOM 容器（流式渲染写到这里，切换时保持存活） */
  containerEl: HTMLDivElement
  /** 容器是否已挂载到当前文档 */
  mounted: boolean
}

// 全局 Map，存活于组件生命周期之外（切换不丢失）
const sessions = new Map<string, SessionState>()

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
) {
  const activeId = ref<string | null>(null)

  const activeSession = computed(() => {
    const id = activeId.value
    return id ? getOrCreate(id) : null
  })

  const messages = computed(() => (activeSession.value?.messages ?? []) as ChatMessage[])
  const loading = computed(() => activeSession.value?.loading ?? false)
  const activeContainer = computed(() => activeSession.value?.containerEl ?? null)

  /** 挂载容器到页面 */
  function mountContainer(session: SessionState, parent: HTMLElement): void {
    if (session.mounted) return
    // 确保元素在 DOM 中（即使 display:none）
    if (!session.containerEl.parentElement) {
      parent.appendChild(session.containerEl)
    }
    session.containerEl.style.display = ''
    session.mounted = true
  }

  /** 隐藏容器（保留在 DOM 中，renderer 可继续写入） */
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

  function ensureActive(createId: string): void {
    if (!activeId.value) switchTo(createId)
  }

  async function send(content: string): Promise<void> {
    const s = activeSession.value
    if (!s || !content.trim() || s.loading) return

    s.messages.push({ role: 'user', content })
    s.loading = true
    s.messages.push({ role: 'assistant', content: '' })

    s.abort = new AbortController()
    const thisRequestId = ++s.requestId
    const sessionId = s.id

    try {
      const response = await fetch(`${API_BASE}/api/chat/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'x-user-id': 'demo-user', 'x-tenant-id': 'default' },
        body: JSON.stringify({ content }),
        signal: s.abort.signal,
      })

      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const reader = response.body?.getReader()
      if (!reader) throw new Error('No reader')

      const decoder = new TextDecoder()
      let buf = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buf += decoder.decode(value, { stream: true })
        const events = buf.split('\n\n')
        buf = events.pop() || ''

        for (const rawEvent of events) {
          const dataLines: string[] = []
          for (const line of rawEvent.split('\n')) {
            if (!line.startsWith('data:')) continue
            dataLines.push(line.startsWith('data: ') ? line.substring(6) : line.substring(5))
          }
          if (dataLines.length === 0) continue
          const data = dataLines.join('\n')
          if (!data || data === '[DONE]') continue

          const msgs = s.messages
          const lastMsg = msgs[msgs.length - 1]
          if (lastMsg && lastMsg.role === 'assistant') lastMsg.content += data

          // 回调渲染器（无论是否活跃——容器始终在 DOM 中可用）
          onChunk?.(sessionId, data)
        }

        if (events.length > 0) await new Promise(r => setTimeout(r, 0))
      }
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        const lastMsg = s.messages[s.messages.length - 1]
        if (lastMsg) lastMsg.content = `请求失败: ${err.message}`
      }
    } finally {
      if (thisRequestId === s.requestId) {
        s.loading = false
        onSessionEnd?.(sessionId)
      }
    }
  }

  function stop(): void {
    const s = activeSession.value
    if (!s) return
    s.requestId++
    s.abort?.abort()
    s.loading = false
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
    switchTo, ensureActive, send, stop, clearSession,
    getMessages, setMessages, destroySession,
    mountContainer, unmountContainer, getOrCreate,
  }
}
