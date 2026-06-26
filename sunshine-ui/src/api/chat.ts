import { ref, type Ref } from 'vue'
import { applyStreamError } from './streamError'
import { parseSseEvent } from './sseParse'
import { normalizeStreamChunk } from './streamInvisible'
import type { ProcessingStep } from './processingSteps'
import type { ExecutionPreference } from './executionModes'

function extractChunkText(data: string): string | null {
  try {
    const obj = JSON.parse(data) as { type?: string; text?: string }
    if (obj.type === 'reasoning') return null
    if ((obj.type === 'content' || obj.type === 'chunk') && typeof obj.text === 'string') {
      return normalizeStreamChunk(obj.text)
    }
    if (obj.type === 'conversation' || obj.type === 'message' || obj.type === 'generation' || obj.type === 'step') {
      return null
    }
  } catch { /* plain text fallback */ }
  return normalizeStreamChunk(data)
}

import { apiHeaders } from '../stores/authStore'
import { ApiError, throwIfHttpError } from './apiError'

// 直连 Gateway SSE（避免 Vite proxy 缓冲）
const API_BASE = import.meta.env.VITE_BFF_STREAM_BASE ?? 'http://localhost:8000'

export interface ChatMessage {
  id?: string
  role: 'user' | 'assistant'
  content: string
  /** user 消息发送时的 executionPreference */
  executionPreference?: ExecutionPreference
  /** 模型推理过程（SSE type:reasoning，不落库） */
  reasoning?: string
  /** 后端处理流水线步骤（SSE type:step） */
  steps?: ProcessingStep[]
  status?: 'streaming' | 'interrupted' | 'failed' | 'completed'
  /** 流式失败时的用户可见错误（与正文分离展示） */
  streamError?: string
  intent?: string
  executionPlanId?: string
}

export function useChat(onChunk?: (data: string) => void) {
  const messages: Ref<ChatMessage[]> = ref([])
  const loading = ref(false)
  let abort: AbortController | null = null
  let requestId = 0

  async function send(content: string) {
    if (!content.trim() || loading.value) return

    messages.value.push({ role: 'user', content })
    loading.value = true

    // 先 push 空消息占位，再从数组中取出 Vue 响应式代理的引用
    // 关键：必须通过 reactive proxy 修改内容，否则 Vue 不会触发 DOM 更新！
    messages.value.push({ role: 'assistant', content: '' })
    abort = new AbortController()
    const thisRequestId = ++requestId

    try {
      const response = await fetch(`${API_BASE}/api/chat/stream`, {
        method: 'POST',
        headers: {
          ...apiHeaders(),
          Accept: 'text/event-stream',
        },
        body: JSON.stringify({ content }),
        signal: abort.signal,
      })

      if (!response.ok) {
        await throwIfHttpError(response)
      }

      const reader = response.body?.getReader()
      if (!reader) throw new ApiError('服务响应异常，请稍后重试', { kind: 'parse' })

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // SSE 事件以 \n\n 分隔（空行 = 事件边界）
        const events = buffer.split('\n\n')
        // 保留最后一个可能不完整的事件
        buffer = events.pop() || ''

        for (const rawEvent of events) {
          const { payload: data } = parseSseEvent(rawEvent)
          if (data === null) continue

          const chunkText = extractChunkText(data)
          if (chunkText === null) continue

          const lastMsg = messages.value[messages.value.length - 1]
          lastMsg.content += chunkText
          onChunk?.(chunkText)
        }

        // 让出主线程给 Vue 渲染
        if (events.length > 0) {
          await new Promise(r => setTimeout(r, 0))
        }
      }
    } catch (err: unknown) {
      applyStreamError(messages.value, err)
    } finally {
      if (thisRequestId === requestId) {
        loading.value = false
      }
    }
  }

  function stop() {
    requestId++ // 使旧请求的 finally 失效
    abort?.abort()
    loading.value = false
  }

  function clear() {
    messages.value = []
  }

  return { messages, loading, send, stop, clear }
}
