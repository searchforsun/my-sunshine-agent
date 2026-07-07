/**
 * 多会话注册表 —— session Map、DOM 容器与生命周期
 */
import { reactive } from 'vue'
import type { ChatMessage } from './chat'
import type { ExecutionPreference } from './executionModes'

export interface SendOptions {
  executionPreference?: ExecutionPreference
  workflowId?: string | null
  skillId?: string
  kbId?: string | null
}

export interface SessionState {
  id: string
  messages: ChatMessage[]
  loading: boolean
  abort: AbortController | null
  requestId: number
  generationId?: string
  streamRevision: number
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
    }
  })
}

export function getSessionRegistry(): Map<string, SessionState> {
  return sessions
}

export function appendChunk(existing: string, chunk: string): string {
  const maxOverlap = Math.min(existing.length, chunk.length, 64)
  for (let n = maxOverlap; n > 0; n--) {
    if (existing.endsWith(chunk.slice(0, n))) return existing + chunk.slice(n)
  }
  return existing + chunk
}

export function getOrCreateSession(id: string): SessionState {
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
      streamRevision: 0,
      containerEl: el,
      mounted: false,
    }) as SessionState)
  }
  return sessions.get(id)!
}
