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
      if (!reader) throw new Error('No reader')

      const decoder = new TextDecoder()
      let buffer = ''
      let seen = new Set<string>()

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        // Keep incomplete last line in buffer
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const data = line.substring(5).trim()
          if (!data || data === '[DONE]') continue

          // Deduplicate — skip chunks already seen (ReActAgent may repeat content)
          const key = data.substring(0, 40)
          if (seen.has(key)) continue
          seen.add(key)

          assistantMsg.content += data
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
