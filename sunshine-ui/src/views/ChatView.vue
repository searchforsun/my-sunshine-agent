<script setup lang="ts">
import { ref, nextTick, watch, onMounted, onUpdated } from 'vue'
import { useChat } from '../api/chat'
import { NInput, NButton, NAvatar, NSpace, NTag } from 'naive-ui'
import MarkdownIt from 'markdown-it'
import markdownItHighlightjs from 'markdown-it-highlightjs'
import markdownItTaskLists from 'markdown-it-task-lists'
import 'highlight.js/styles/github-dark.css'
import { StreamMarkdownRenderer } from '../utils/stream-markdown'
import { enhanceStaticMarkdown, reRenderStaticMermaids } from '../utils/stream-markdown/StaticEnhancer'
import '../utils/stream-markdown/styles.css'
import hljs from 'highlight.js/lib/core'
import { useChatStore } from '../stores/chatStore'
import { useTheme } from '../composables/useTheme'

hljs.registerLanguage('mermaid', () => ({ contains: [] }))

const md = new MarkdownIt({
  html: true, breaks: true, linkify: true, typographer: true,
}).use(markdownItHighlightjs).use(markdownItTaskLists)

// ── 流式 Markdown 渲染引擎 ──
let streamRenderer: StreamMarkdownRenderer | null = null
const settledHtml = ref('') // 流式完成后的最终 HTML（防止 v-html 切换导致 DOM 丢失）

const { messages, loading, send, stop, clear } = useChat(async (chunk) => {
  if (!streamRenderer) {
    // 等 Vue 渲染出 streaming div 后再查询 DOM
    await nextTick()
    const container = document.querySelector('.msg-md.streaming')
    if (container instanceof HTMLElement) {
      streamRenderer = new StreamMarkdownRenderer(container, {
        debounceMs: 50,
        renderMarkdown: (text: string) => {
          try { return md.render(text) } catch { return text }
        },
      })
    }
  }
  streamRenderer?.processChunk(chunk)
})
const inputText = ref('')
const inputRef = ref<InstanceType<typeof NInput>>()
const streamingContentRef = ref<HTMLElement>()

const chatStore = useChatStore()
const { theme } = useTheme()

function enhanceAllStatic() {
  nextTick(() => {
    document.querySelectorAll('.msg-md:not(.streaming)').forEach(el => {
      if (el instanceof HTMLElement) enhanceStaticMarkdown(el)
    })
  })
}

function scrollToBottom() {
  const el = document.querySelector('.content-area')
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  try { return md.render(text) } catch { return text.replace(/</g, '&lt;').replace(/>/g, '&gt;') }
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  // 首条用户消息自动生成标题
  if (chatStore.currentId && messages.value.length === 0) {
    chatStore.updateTitle(chatStore.currentId, text)
  }

  inputText.value = ''
  settledHtml.value = '' // 新消息清掉旧 HTML
  streamRenderer?.clear()
  streamRenderer = null

  await send(text)
  await nextTick()
  scrollToBottom()
}

function handleClear() {
  clear()
  settledHtml.value = ''
  streamRenderer?.clear()
  streamRenderer = null
  if (chatStore.currentId) {
    chatStore.syncMessages(chatStore.currentId, [])
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

onMounted(() => {
  chatStore.ensureCurrent()
  // 加载已有消息
  if (chatStore.current && chatStore.current.messages.length > 0) {
    messages.value = [...chatStore.current.messages]
  }
  enhanceAllStatic()
  inputRef.value?.focus()
})

// 每次 DOM 更新后增强静态渲染的消息（代码头、Mermaid 图表）
onUpdated(() => {
  enhanceAllStatic()
})

// 主题切换时重渲染所有 Mermaid 图表
watch(theme, () => {
  nextTick(() => reRenderStaticMermaids())
})

// 侧边栏切换对话时同步
watch(() => chatStore.currentId, (newId, oldId) => {
  if (!newId || newId === oldId) return
  if (loading.value) stop()
  // 保存当前对话
  if (oldId && messages.value.length > 0) {
    chatStore.syncMessages(oldId, [...messages.value])
  }
  // 加载新对话
  const conv = chatStore.current
  messages.value = conv?.messages?.length ? [...conv.messages] : []
  settledHtml.value = ''
  streamRenderer?.clear()
  streamRenderer = null
  enhanceAllStatic()
})

// 自动滚动（内容变化时）
watch(
  () => {
    const last = messages.value[messages.value.length - 1]
    return last?.role === 'assistant' ? last.content.length : 0
  },
  async () => {
    await nextTick()
    scrollToBottom()
  },
)

// 流式结束：flush: 'sync' 确保在 Vue 销毁 streaming div 之前保存 HTML
watch(() => loading.value, (val) => {
  if (!val && streamRenderer) {
    streamRenderer.finish()
    const container = document.querySelector('.msg-md.streaming')
    if (container) settledHtml.value = container.innerHTML
    streamRenderer = null
    // 同步消息到 store
    if (chatStore.currentId) {
      chatStore.syncMessages(chatStore.currentId, [...messages.value])
    }
  }
}, { flush: 'sync' })
</script>

<template>
  <div class="chat-root">
    <header class="chat-header">
      <div>
        <h2 class="chat-title">AI 智能助手</h2>
        <p class="chat-subtitle">ReActAgent · 知识库增强 · 流式输出</p>
      </div>
      <NButton text size="small" @click="handleClear" :disabled="messages.length === 0" class="clear-btn">
        清空对话
      </NButton>
    </header>

    <div class="chat-body">
      <div v-if="messages.length === 0" class="empty-state">
        <div class="empty-glow">
          <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
            <circle cx="24" cy="24" r="14" stroke="#f59e0b" stroke-width="1.2" opacity="0.4" />
            <circle cx="24" cy="24" r="8" fill="#f59e0b" opacity="0.15" />
            <circle cx="24" cy="24" r="4" fill="#f59e0b" opacity="0.4" />
          </svg>
        </div>
        <h3>开始对话</h3>
        <p>向 AI 助手提问，知识库已就绪。</p>
        <div class="hint-chips">
          <button class="hint-chip" @click="inputText='介绍一下 RAG 的原理'; handleSend()">RAG 原理</button>
          <button class="hint-chip" @click="inputText='考勤制度是什么？'; handleSend()">检索知识库</button>
        </div>
      </div>

      <div class="msg-list">
        <div v-for="(msg, idx) in messages" :key="idx" class="msg-row" :class="msg.role">
          <div class="msg-avatar">
            <NAvatar :size="34" :style="{
              background: msg.role === 'user'
                ? 'linear-gradient(135deg, #3b82f6, #1d4ed8)'
                : 'linear-gradient(135deg, #f59e0b, #d97706)'
            }">
              {{ msg.role === 'user' ? '我' : 'AI' }}
            </NAvatar>
          </div>
          <div class="msg-bubble">
            <div class="msg-meta">
              <span class="msg-sender">{{ msg.role === 'user' ? '我' : 'AI 助手' }}</span>
              <NTag
                v-if="msg.role === 'assistant' && loading && idx === messages.length - 1"
                size="tiny" :bordered="false" type="warning"
              >
                <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
              </NTag>
            </div>
            <!-- AI 消息：流式中的最后一条用 StreamMarkdownRenderer 直接写 DOM -->
            <div
              v-if="msg.role === 'assistant' && loading && idx === messages.length - 1"
              ref="streamingContentRef"
              class="msg-md streaming"
            />
            <!-- AI 消息：流式刚完成，保存的 HTML（防止 Vue 切换丢失 Mermaid SVG） -->
            <div
              v-else-if="msg.role === 'assistant' && settledHtml && idx === messages.length - 1"
              class="msg-md"
              v-html="settledHtml"
            />
            <!-- AI 消息：历史消息，用 markdown-it 直接渲染 -->
            <div
              v-else-if="msg.role === 'assistant'"
              class="msg-md"
              v-html="renderMarkdown(msg.content || '')"
            />
            <div v-else class="msg-text">{{ msg.content }}</div>
          </div>
        </div>
      </div>
    </div>

    <footer class="chat-footer">
      <div class="input-wrapper">
        <NInput ref="inputRef" v-model:value="inputText" type="textarea"
          placeholder="输入消息（Enter 发送，Shift+Enter 换行）"
          :autosize="{ minRows: 1, maxRows: 4 }" :disabled="loading"
          @keydown="handleKeydown" class="chat-input" round size="large" />
        <div class="input-actions">
          <span class="char-hint" v-if="loading"><span class="pulse-dot online"/> AI 回复中...</span>
          <span class="char-hint" v-else>就绪</span>
          <NSpace>
            <NButton v-if="loading" @click="stop" type="error" size="small" secondary round>停止</NButton>
            <NButton @click="handleSend" type="warning" size="small" :disabled="!inputText.trim() || loading" round>
              <template #icon>
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2 8l12-6-6 12-2-6-4-0z" fill="currentColor"/></svg>
              </template>
              发送
            </NButton>
          </NSpace>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat-root { display: flex; flex-direction: column; min-height: 100vh; max-width: 820px; margin: 0 auto; padding: 0 24px; }
.chat-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  padding: 20px 0 12px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
  position: sticky; top: 0; z-index: 10;
  background: var(--sun-black);
}
.chat-title { font-size: 20px; font-weight: 700; letter-spacing: -0.4px; color: var(--sun-text); margin: 0; }
.chat-subtitle { font-size: 12.5px; color: var(--sun-text-muted); margin: 2px 0 0; }
.clear-btn { color: var(--sun-text-muted) !important; font-size: 12px; }
.chat-body { flex: 1; display: flex; flex-direction: column; }

.empty-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.empty-glow { margin-bottom: 20px; animation: glow-pulse 3s ease-in-out infinite; }
.empty-state h3 { font-size: 20px; font-weight: 600; color: var(--sun-text); margin-bottom: 6px; }
.empty-state p { font-size: 14px; color: var(--sun-text-muted); max-width: 360px; line-height: 1.5; }
.hint-chips { display: flex; gap: 8px; margin-top: 20px; flex-wrap: wrap; justify-content: center; }
.hint-chip { padding: 7px 15px; background: var(--sun-surface); border: 1px solid var(--sun-border); border-radius: 20px; color: var(--sun-text-secondary); font-size: 13px; cursor: pointer; transition: all .2s; font-family: inherit; }
.hint-chip:hover { border-color: var(--sun-amber); color: var(--sun-amber-light); background: var(--sun-amber-glow); }

.msg-list { padding: 16px 0 8px; display: flex; flex-direction: column; gap: 6px; }
.msg-row { display: flex; gap: 12px; padding: 12px 16px; border-radius: var(--radius-lg); animation: fade-in-up .35s var(--ease-out-expo) forwards; }
.msg-avatar { flex-shrink: 0; padding-top: 2px; }
.msg-bubble { flex: 1; min-width: 0; }
.msg-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.msg-sender { font-size: 12px; font-weight: 600; color: var(--sun-text-muted); text-transform: uppercase; letter-spacing: .5px; }
.msg-text { font-size: 14.5px; line-height: 1.75; white-space: pre-wrap; word-break: break-word; color: var(--sun-text); }

.typing-dots { display: inline-flex; gap: 3px; align-items: center; }
.typing-dots .dot { width: 4px; height: 4px; border-radius: 50%; background: var(--sun-amber); animation: dot-bounce 1.3s ease-in-out infinite; }
.typing-dots .dot:nth-child(2) { animation-delay: .15s; }
.typing-dots .dot:nth-child(3) { animation-delay: .3s; }

.chat-footer { flex-shrink: 0; padding: 12px 0 20px; background: var(--sun-black); position: sticky; bottom: 0; }
.input-wrapper { background: var(--sun-deep); border: 1px solid var(--sun-border); border-radius: var(--radius-xl); padding: 10px 14px 12px; transition: border-color .3s; }
.input-wrapper:focus-within { border-color: var(--sun-amber); box-shadow: 0 0 0 3px var(--sun-amber-glow); }
.chat-input { --n-border: none !important; --n-border-hover: none !important; --n-border-focus: none !important; --n-box-shadow-focus: none !important; --n-color: transparent !important; --n-color-focus: transparent !important; }
.input-actions { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--sun-border); }
.char-hint { font-size: 11.5px; color: var(--sun-text-muted); display: flex; align-items: center; gap: 6px; font-family: 'JetBrains Mono', monospace; }

/* Markdown rendered content */
.msg-md { font-size: 14.5px; line-height: 1.8; color: var(--sun-text); word-break: break-word; }
.msg-md :deep(h1), .msg-md :deep(h2), .msg-md :deep(h3) { margin: 16px 0 8px; font-weight: 700; color: var(--sun-text); }
.msg-md :deep(h3) { font-size: 15px; }
.msg-md :deep(h2) { font-size: 17px; border-bottom: 1px solid var(--sun-border); padding-bottom: 6px; }
.msg-md :deep(h1) { font-size: 19px; border-bottom: 1px solid var(--sun-border); padding-bottom: 6px; }
.msg-md :deep(p) { margin: 6px 0; }
.msg-md :deep(strong) { color: var(--sun-amber-light); font-weight: 600; }
.msg-md :deep(a) { color: var(--sun-blue); }
.msg-md :deep(ul), .msg-md :deep(ol) { margin: 8px 0; padding-left: 22px; }
.msg-md :deep(li) { margin: 3px 0; }
.msg-md :deep(hr) { border: none; border-top: 1px solid var(--sun-border); margin: 16px 0; }
.msg-md :deep(blockquote) { border-left: 3px solid var(--sun-amber); padding: 4px 14px; margin: 12px 0; color: var(--sun-text-secondary); }
.msg-md :deep(code) { background: rgba(245, 158, 11, 0.08); color: var(--sun-amber-light); padding: 2px 6px; border-radius: 4px; font-family: 'JetBrains Mono', monospace; font-size: 13px; }
.msg-md :deep(pre) { background: var(--sun-deep); border: 1px solid var(--sun-border); border-radius: var(--radius-md); padding: 14px 18px; overflow-x: auto; margin: 12px 0; }
.msg-md :deep(pre code) { background: none; color: var(--sun-text); padding: 0; font-size: 13px; }
.msg-md :deep(table) { border-collapse: collapse; margin: 12px 0; width: 100%; }
.msg-md :deep(th), .msg-md :deep(td) { border: 1px solid var(--sun-border); padding: 8px 14px; text-align: left; }
.msg-md :deep(th) { background: var(--sun-deep); font-weight: 600; }
.msg-md :deep(img) { max-width: 100%; border-radius: 8px; }
.msg-md :deep(.mermaid-container) { display: flex; justify-content: center; margin: 16px 0; padding: 16px; background: var(--sun-deep); border: 1px solid var(--sun-border); border-radius: var(--radius-md); overflow-x: auto; }
.msg-md :deep(.mermaid-container svg) { max-width: 100%; height: auto; }

</style>
