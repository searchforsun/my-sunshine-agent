import { nextTick, ref, watch, type Ref } from 'vue'
import type MarkdownIt from 'markdown-it'
import type { ChatMessage } from '../api/chat'
import { resolveAssistantDisplayContent } from '../api/streamError'
import { resolveStreamingContentText } from '../api/contentInterleave'
import { normalizeStreamingMarkdown } from '../utils/stream-markdown/normalizeStreamingMarkdown'
import { StreamMarkdownRenderer } from '../utils/stream-markdown'
import { enhanceStaticMarkdown } from '../utils/stream-markdown/StaticEnhancer'

/** 流式 / 终态 Markdown 渲染与 settledHtml 缓存 */
export function useChatStreamMarkdown(
  md: MarkdownIt,
  messages: Ref<ChatMessage[]>,
  loading: Ref<boolean>,
  currentConversationId: Ref<string | null | undefined>,
  scrollToBottom: (force?: boolean) => void,
  forceChatScroll: Ref<boolean>,
) {
  let streamRenderer: StreamMarkdownRenderer | null = null
  const settledHtml = ref('')
  const sessionSettledHtml = new Map<string, string>()
  const streamingMdRef = ref<HTMLElement | null>(null)

  function setStreamingMdRef(el: unknown) {
    streamingMdRef.value = el instanceof HTMLElement ? el : null
  }

  function renderMarkdown(text: string): string {
    if (!text) return ''
    const normalized = normalizeStreamingMarkdown(text)
    try { return md.render(normalized) } catch { return normalized.replace(/</g, '&lt;').replace(/>/g, '&gt;') }
  }

  function captureSettledAssistantHtml(content: string): string {
    return renderMarkdown(content)
  }

  function renderAssistantHtml(msg: ChatMessage, idx: number): string {
    if (idx === messages.value.length - 1 && settledHtml.value && !loading.value) {
      return settledHtml.value
    }
    return renderMarkdown(resolveAssistantDisplayContent(msg))
  }

  function enhanceAllStaticMarkdown(): void {
    document.querySelectorAll('.msg-md:not(.streaming)').forEach(el => {
      if (el instanceof HTMLElement) enhanceStaticMarkdown(el)
    })
  }

  async function ensureStreamRenderer(retries = 5): Promise<void> {
    for (let i = 0; i < retries; i++) {
      await nextTick()
      if (streamingMdRef.value) break
    }
    const container = streamingMdRef.value
    if (!container) return
    streamRenderer?.clear()
    streamRenderer = new StreamMarkdownRenderer(container, {
      debounceMs: 16,
      renderMarkdown: (text: string) => {
        try { return md.render(normalizeStreamingMarkdown(text)) } catch { return text }
      },
    })
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant' && resolveStreamingContentText(last)) {
      streamRenderer.syncFromContent(resolveStreamingContentText(last))
    }
  }

  function syncStreamFromContent(content: string) {
    streamRenderer?.syncFromContent(content)
  }

  function clearStreamRenderer() {
    streamRenderer?.clear()
    streamRenderer = null
  }

  function cacheSettledHtmlForConversation(convId: string) {
    if (settledHtml.value) sessionSettledHtml.set(convId, settledHtml.value)
  }

  function restoreSettledHtmlForConversation(convId: string) {
    settledHtml.value = sessionSettledHtml.get(convId) ?? ''
  }

  function applySettledFromLastAssistant(last: ChatMessage | undefined, convId: string | null | undefined) {
    if (last?.content?.trim() && !loading.value) {
      settledHtml.value = captureSettledAssistantHtml(resolveAssistantDisplayContent(last))
      if (convId) sessionSettledHtml.set(convId, settledHtml.value)
    } else if (!loading.value) {
      settledHtml.value = convId ? (sessionSettledHtml.get(convId) ?? '') : ''
    }
  }

  watch(() => loading.value, async (val) => {
    if (val) {
      await nextTick()
      await ensureStreamRenderer()
      return
    }
    if (forceChatScroll.value) {
      forceChatScroll.value = false
      await nextTick()
      scrollToBottom(true)
    }
    if (streamRenderer) {
      streamRenderer.finish()
      const last = messages.value[messages.value.length - 1]
      if (last?.role === 'assistant' && last.content) {
        settledHtml.value = captureSettledAssistantHtml(resolveAssistantDisplayContent(last))
        if (currentConversationId.value) {
          sessionSettledHtml.set(currentConversationId.value, settledHtml.value)
        }
      } else {
        settledHtml.value = ''
      }
      streamRenderer = null
      nextTick(() => enhanceAllStaticMarkdown())
    }
  }, { flush: 'sync' })

  return {
    settledHtml,
    sessionSettledHtml,
    streamingMdRef,
    setStreamingMdRef,
    renderMarkdown,
    captureSettledAssistantHtml,
    renderAssistantHtml,
    enhanceAllStaticMarkdown,
    ensureStreamRenderer,
    syncStreamFromContent,
    clearStreamRenderer,
    cacheSettledHtmlForConversation,
    restoreSettledHtmlForConversation,
    applySettledFromLastAssistant,
  }
}
