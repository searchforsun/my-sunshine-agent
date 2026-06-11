import { ref, type Ref } from 'vue'
import { applyStreamError } from './streamError'

// 直连 BFF，不经过 Vite proxy（proxy 会缓冲 SSE 流式响应）
const API_BASE = 'http://localhost:8001'

export interface ChatMessage {
  id?: string
  role: 'user' | 'assistant'
  content: string
  status?: 'streaming' | 'interrupted' | 'failed' | 'completed'
  intent?: string
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
          'Content-Type': 'application/json',
          'x-user-id': 'demo-user',
          'x-tenant-id': 'default',
        },
        body: JSON.stringify({ content }),
        signal: abort.signal,
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }

      const reader = response.body?.getReader()
      if (!reader) throw new Error('No reader')

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
          // 提取事件中所有 data: 行
          const dataLines: string[] = []
          for (const line of rawEvent.split('\n')) {
            if (!line.startsWith('data:')) continue
            let payload: string
            if (line.startsWith('data: ')) {
              payload = line.substring(6)
            } else {
              payload = line.substring(5)
            }
            dataLines.push(payload)
          }

          if (dataLines.length === 0) continue

          // SSE 规范：多行 data 用 \n 拼接
          const data = dataLines.join('\n')

          if (!data || data === '[DONE]') continue

          // 通过 Vue 响应式代理引用修改内容
          const lastMsg = messages.value[messages.value.length - 1]
          lastMsg.content += data
          // 通知外部渲染器（流式 Markdown 引擎）
          onChunk?.(data)
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
