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
  /** 会话绑定模型（注册表 model_name）；空串清绑定 */
  modelName?: string | null
  /** never | always | smart — 沙箱写 HITL 跳过 */
  writeHitlMode?: 'never' | 'always' | 'smart'
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

/** reasoning 等通道：后端增量帧直接追加（续跑前已清空旧 reasoning） */
export function appendChunk(existing: string, chunk: string): string {
  return (existing ?? '') + (chunk ?? '')
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
