import type { ChatMessage } from './chat'

let pageUnloading = false

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => {
    pageUnloading = true
  })
}

export function isPageUnloading(): boolean {
  return pageUnloading
}

export function isTransientNetworkError(err: unknown): boolean {
  if (!(err instanceof Error)) return false
  const msg = err.message.toLowerCase()
  return (
    msg.includes('network error')
    || msg.includes('failed to fetch')
    || msg.includes('load failed')
    || msg.includes('networkerror')
    || (err.name === 'TypeError' && msg.includes('fetch'))
  )
}

export function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError'
}

/**
 * 流式请求异常：保留已输出内容，刷新/断网时不整段覆盖为错误文案
 */
export function applyStreamError(messages: ChatMessage[], err: unknown): void {
  if (isAbortError(err) || isPageUnloading()) return

  const lastMsg = messages[messages.length - 1]
  if (!lastMsg || lastMsg.role !== 'assistant') return

  const hasPartial = lastMsg.content.trim().length > 0
  const transient = isTransientNetworkError(err)

  if (transient) {
    if (!hasPartial) {
      messages.pop()
    }
    return
  }

  const detail = err instanceof Error ? err.message : String(err)
  if (hasPartial) {
    lastMsg.content += `\n\n---\n*请求失败: ${detail}*`
  } else {
    lastMsg.content = `请求失败: ${detail}`
  }
}

/** 清理刷新时误写入 localStorage 的纯错误占位 */
export function sanitizeRestoredMessages(msgs: ChatMessage[]): ChatMessage[] {
  if (msgs.length === 0) return msgs
  const last = msgs[msgs.length - 1]
  if (last.role !== 'assistant') return msgs
  const text = last.content.trim()
  if (/^请求失败:\s*(network error|failed to fetch|load failed)/i.test(text)) {
    return msgs.slice(0, -1)
  }
  return msgs
}
