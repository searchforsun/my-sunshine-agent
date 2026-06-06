import { ref, type Ref } from 'vue'

const API_BASE = 'http://localhost:8001'

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export function useChat() {
  const messages: Ref<ChatMessage[]> = ref([])
  const loading = ref(false)
  let abort: AbortController | null = null

  async function send(content: string) {
    if (!content.trim() || loading.value) return

    messages.value.push({ role: 'user', content })
    loading.value = true

    const assistantMsg: ChatMessage = { role: 'assistant', content: '' }
    messages.value.push(assistantMsg)
    abort = new AbortController()

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
      if (!reader) {
        throw new Error('No reader')
      }

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.substring(5).trim()
            if (data && data !== '[DONE]') {
              assistantMsg.content += data
            }
          }
        }
      }
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        assistantMsg.content = `请求失败: ${err.message}`
      }
    } finally {
      loading.value = false
    }
  }

  function stop() {
    abort?.abort()
    loading.value = false
  }

  function clear() {
    messages.value = []
  }

  return { messages, loading, send, stop, clear }
}
