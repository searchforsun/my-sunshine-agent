import type { ChatMessage } from './chat'
import { normalizeRestoredInterleavedContent } from './contentInterleave'
import { ApiError, friendlyErrorMessage } from './apiError'

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

/** 识别落库正文中的技术栈/流式失败文案 */
export function isLikelyStreamFailureContent(text: string): boolean {
  const t = text.trim()
  if (!t) return false
  if (/block\(\)\/blockFirst\(\)\/blockLast\(\)/i.test(t)) return true
  if (/reactor-http-nio/i.test(t)) return true
  if (/^请求失败:/i.test(t)) return true
  if (/^(java\.|org\.springframework\.|reactor\.)/i.test(t)) return true
  return false
}

function stripTrailingStreamError(content: string, error: string): string {
  const c = content.trimEnd()
  const e = error.trim()
  if (!c || !e) return content
  if (c === e) return ''
  if (c.endsWith(`\n\n${e}`)) return c.slice(0, -(e.length + 2)).trimEnd()
  if (c.endsWith(e)) return c.slice(0, -e.length).trimEnd()
  return content
}

/** 将 content 中尾部错误拆到 streamError，避免正文与错误条重复 */
export function splitContentAndStreamError(
  msg: Pick<ChatMessage, 'content' | 'streamError' | 'status'>,
): { content: string; streamError?: string } {
  const existing = msg.streamError?.trim()
  if (existing) {
    return {
      content: stripTrailingStreamError(msg.content ?? '', existing),
      streamError: existing,
    }
  }
  const raw = msg.content ?? ''
  const trimmed = raw.trim()
  if (!trimmed) return { content: raw }

  const isFailed = msg.status === 'failed'
  const splitAt = raw.lastIndexOf('\n\n')
  if (splitAt >= 0) {
    const head = raw.slice(0, splitAt).trim()
    const tail = raw.slice(splitAt + 2).trim()
    if (tail && (isLikelyStreamFailureContent(tail) || (isFailed && !head))) {
      return { content: head, streamError: tail }
    }
  }

  if (isLikelyStreamFailureContent(trimmed)) {
    return { content: '', streamError: trimmed }
  }

  // 仅 failed 且无正文分隔时，整段 content 视为错误落库（暂停 interrupted 保留正文）
  if (isFailed && !raw.includes('\n\n')) {
    return { content: '', streamError: trimmed }
  }

  return { content: raw }
}

export function resolveAssistantDisplayContent(
  msg: Pick<ChatMessage, 'content' | 'streamError' | 'status' | 'steps'>,
): string {
  if (msg.steps?.some(s => s.phase === 'plan')) {
    const fromStep = msg.steps.find(s => s.id === 'node-answer')?.result?.trim()
    if (fromStep) return fromStep
  }
  return splitContentAndStreamError(msg).content
}

export function resolveStreamErrorText(
  msg: Pick<ChatMessage, 'content' | 'streamError' | 'status'>,
): string | undefined {
  return splitContentAndStreamError(msg).streamError
}

/** 从落库正文恢复 streamError（刷新 / hydrate 后） */
export function hydrateStreamError(msg: ChatMessage): void {
  const split = splitContentAndStreamError(msg)
  if (!split.streamError) return
  msg.streamError = split.streamError
  msg.content = split.content
}

/** 流式失败：写入 streamError + 终态，供错误条与「继续生成」使用 */
export function applyStreamFailure(msg: ChatMessage, err: unknown): void {
  if (isAbortError(err) || isPageUnloading()) return
  const detail = err instanceof ApiError
    ? err.message
    : friendlyErrorMessage(err, '生成失败，请稍后重试')
  msg.streamError = detail
  msg.content = stripTrailingStreamError(msg.content ?? '', detail)
  msg.status = isTransientNetworkError(err) ? 'interrupted' : 'failed'
}

export function applyStreamErrorFromText(msg: ChatMessage, text: string): void {
  const detail = text.trim()
  if (!detail) return
  msg.streamError = detail
  msg.content = stripTrailingStreamError(msg.content ?? '', detail)
  if (msg.status !== 'failed' && msg.status !== 'interrupted') {
    msg.status = 'failed'
  }
}

/** 流式请求异常（messages 数组版） */
export function applyStreamError(messages: ChatMessage[], err: unknown): void {
  if (isAbortError(err) || isPageUnloading()) return
  const lastMsg = messages[messages.length - 1]
  if (!lastMsg || lastMsg.role !== 'assistant') return
  const hasPartial = lastMsg.content.trim().length > 0
  const transient = isTransientNetworkError(err)
  if (transient && !hasPartial && !lastMsg.streamError) {
    messages.pop()
    return
  }
  applyStreamFailure(lastMsg, err)
}

/** 清理刷新时误写入 localStorage 的纯错误占位，并恢复 streamError */
export function sanitizeRestoredMessages(msgs: ChatMessage[]): ChatMessage[] {
  if (msgs.length === 0) return msgs
  const last = msgs[msgs.length - 1]
  if (last.role === 'assistant') {
    const text = last.content.trim()
    if (/^请求失败:\s*(network error|failed to fetch|load failed)/i.test(text)) {
      return msgs.slice(0, -1)
    }
  }
  for (const m of msgs) {
    if (m.role !== 'assistant') continue
    // 历史 REST 落库仍为 streaming 的 assistant（中断/关页），新窗口加载时归一化终态
    if (m.status === 'streaming') {
      const hasPartial = !!(m.content?.trim() || m.reasoning?.trim() || m.steps?.length)
      if (m.executionPlanId && !hasPartial) {
        m.status = 'completed'
      } else {
        m.status = hasPartial ? 'interrupted' : 'failed'
      }
    }
    if (m.status === 'failed' || isLikelyStreamFailureContent(m.content)) {
      hydrateStreamError(m)
    }
    normalizeRestoredInterleavedContent(m)
  }
  return msgs
}
