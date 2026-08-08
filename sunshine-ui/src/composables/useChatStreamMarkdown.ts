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
  /** 历史消息 markdown HTML 缓存：content 未变则复用，避免流式 bump 时全量重解析 */
  const assistantHtmlCache = new WeakMap<ChatMessage, { content: string; html: string }>()

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
    const content = resolveAssistantDisplayContent(msg)
    const cached = assistantHtmlCache.get(msg)
    if (cached && cached.content === content) return cached.html
    const html = renderMarkdown(content)
    assistantHtmlCache.set(msg, { content, html })
    return html
  }

  function enhanceAllStaticMarkdown(): void {
    document.querySelectorAll('.msg-md:not(.streaming)').forEach(el => {
      if (!(el instanceof HTMLElement)) return
      // 已扫描过的容器跳过：避免长对话每次更新全量 querySelectorAll + getBoundingClientRect（强制布局）。
      // 视口外容器仅注册 IntersectionObserver，进入视口时由 observer 完成路径增强，不依赖此标记。
      if (el.dataset.smdScanned === '1') return
      enhanceStaticMarkdown(el)
      el.dataset.smdScanned = '1'
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
      debounceMs: 50,
      renderMarkdown: (text: string) => {
        try { return md.render(normalizeStreamingMarkdown(text)) } catch { return text }
      },
    })
    // 重建时重置节流状态，避免旧 trailing timer 影响新流
    if (trailingSyncTimer) { clearTimeout(trailingSyncTimer); trailingSyncTimer = null }
    pendingSyncContent = ''
    lastStreamSyncAt = 0
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant' && resolveStreamingContentText(last)) {
      streamRenderer.syncFromContent(resolveStreamingContentText(last))
    }
  }

  /** 流式正文同步节流：SSE chunk 高频到达时 fullRerender（清空 DOM+markdown-it 全量解析+重建）
   * 阻塞主线程导致正文卡顿。节流保证相邻两次同步至少间隔 50ms，同时用 trailing
   * 确保被跳过的最新内容在节流窗口结束后自动渲染，不会丢失文末 chunk。 */
  let lastStreamSyncAt = 0
  let pendingSyncContent = ''
  let trailingSyncTimer: ReturnType<typeof setTimeout> | null = null
  const STREAM_SYNC_THROTTLE_MS = 50
  function scheduleStreamingContentSync(content: string) {
    pendingSyncContent = content
    const now = Date.now()
    if (now - lastStreamSyncAt < STREAM_SYNC_THROTTLE_MS) {
      // 节流中：安排一个 trailing 回调在窗口结束后渲染最新内容
      if (!trailingSyncTimer) {
        trailingSyncTimer = setTimeout(() => {
          trailingSyncTimer = null
          lastStreamSyncAt = Date.now()
          if (pendingSyncContent) streamRenderer?.syncFromContent(pendingSyncContent)
        }, STREAM_SYNC_THROTTLE_MS - (now - lastStreamSyncAt))
      }
      return
    }
    if (trailingSyncTimer) { clearTimeout(trailingSyncTimer); trailingSyncTimer = null }
    lastStreamSyncAt = now
    streamRenderer?.syncFromContent(content)
  }

  function syncStreamFromContent(content: string) {
    streamRenderer?.syncFromContent(content)
  }

  function clearStreamRenderer() {
    if (trailingSyncTimer) { clearTimeout(trailingSyncTimer); trailingSyncTimer = null }
    pendingSyncContent = ''
    lastStreamSyncAt = 0
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
    if (forceChatScroll.value) {
      forceChatScroll.value = false
      await nextTick()
      scrollToBottom(true)
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
    scheduleStreamingContentSync,
    clearStreamRenderer,
    cacheSettledHtmlForConversation,
    restoreSettledHtmlForConversation,
    applySettledFromLastAssistant,
  }
}
