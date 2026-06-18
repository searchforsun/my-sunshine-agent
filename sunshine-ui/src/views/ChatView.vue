<script setup lang="ts">
import { ref, nextTick, watch, onMounted, onUnmounted, onUpdated, computed, reactive } from 'vue'
import { useChatSessions } from '../api/chatSessions'
import { NInput } from 'naive-ui'
import { createMarkdownIt } from '../utils/markdown/createMarkdownIt'
import 'katex/dist/katex.min.css'
import 'highlight.js/styles/github-dark.css'
import { StreamMarkdownRenderer } from '../utils/stream-markdown'
import { normalizeStreamingMarkdown } from '../utils/stream-markdown/normalizeStreamingMarkdown'
import { enhanceStaticMarkdown, reRenderStaticMermaids } from '../utils/stream-markdown/StaticEnhancer'
import '../utils/stream-markdown/styles.css'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import cpp from 'highlight.js/lib/languages/cpp'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import python from 'highlight.js/lib/languages/python'
import rust from 'highlight.js/lib/languages/rust'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { useChatStore } from '../stores/chatStore'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { apiHeaders } from '../composables/useUserId'
import {
  loadActiveGeneration,
  clearActiveGeneration,
  type ActiveGeneration,
} from '../composables/useActiveGeneration'

/** 挂载/切换会话 hydration 期间，跳过 currentId watch 避免切到空 session */
let sessionHydrating = false
import OperationStack from '../components/operation/OperationStack.vue'
import type { ChatMessage } from '../api/chat'
import {
  normalizeTimelineSteps,
  hasActiveStep,
  type ProcessingStep,
} from '../api/processingSteps'

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('c', cpp)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('python', python)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('ts', typescript)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('mermaid', () => ({ contains: [] }))

const md = createMarkdownIt(hljs)

let streamRenderer: StreamMarkdownRenderer | null = null
const settledHtml = ref('')
const sessionSettledHtml = new Map<string, string>()
const streamingMdRef = ref<HTMLElement | null>(null)

function setStreamingMdRef(el: unknown) {
  streamingMdRef.value = el instanceof HTMLElement ? el : null
}

const chatStore = useChatStore()
const { theme, toggle: toggleTheme } = useTheme()
const isDark = computed(() => theme.value === 'dark')
const { sidebarVisible, toggleSidebar } = useSidebar()

const sessionTitle = computed(() => chatStore.current?.title || '新对话')

/** 流式回复是否已有正文（用于切换等待动画 → 渲染区） */
const streamingHasContent = computed(() => {
  if (!loading.value) return false
  const last = messages.value[messages.value.length - 1]
  return last?.role === 'assistant' && !!last.content?.trim()
})

const reasoningExpanded = reactive(new Map<string, boolean>())
const reasoningUserToggled = reactive(new Set<string>())

function reasoningKey(msg: ChatMessage, idx: number): string {
  return msg.id ?? `_idx_${idx}`
}

function clearReasoningUiState(): void {
  reasoningExpanded.clear()
  reasoningUserToggled.clear()
}

function isReasoningExpanded(msg: ChatMessage, idx: number): boolean {
  if (!msg?.reasoning?.trim()) return false
  const key = reasoningKey(msg, idx)
  if (reasoningUserToggled.has(key)) return reasoningExpanded.get(key) ?? false
  if (loading.value && idx === messages.value.length - 1 && !msg.content?.trim()) return true
  return false
}

function isReasoningLive(msg: ChatMessage, idx: number): boolean {
  return loading.value
    && idx === messages.value.length - 1
    && !!msg?.reasoning?.trim()
    && !msg.content?.trim()
}

function toggleReasoning(msg: ChatMessage, idx: number): void {
  const key = reasoningKey(msg, idx)
  const currentlyExpanded = isReasoningExpanded(msg, idx)
  reasoningUserToggled.add(key)
  reasoningExpanded.set(key, !currentlyExpanded)
}

function resolveTimelineSteps(msg: ChatMessage, idx: number): ProcessingStep[] {
  if (!msg.steps?.length) return []
  return normalizeTimelineSteps(msg.steps, msg.reasoning)
}

function showTimeline(msg: ChatMessage, idx: number): boolean {
  const steps = resolveTimelineSteps(msg, idx)
  return steps.length > 0
}

function isTimelineLive(msg: ChatMessage, idx: number): boolean {
  return loading.value
    && idx === messages.value.length - 1
    && hasActiveStep(resolveTimelineSteps(msg, idx))
}

function migrateReasoningKeys(): void {
  messages.value.forEach((msg, idx) => {
    if (!msg.id) return
    const legacyKey = `_idx_${idx}`
    if (!reasoningUserToggled.has(legacyKey) && !reasoningExpanded.has(legacyKey)) return
    reasoningUserToggled.add(msg.id)
    reasoningUserToggled.delete(legacyKey)
    if (reasoningExpanded.has(legacyKey)) {
      reasoningExpanded.set(msg.id, reasoningExpanded.get(legacyKey)!)
      reasoningExpanded.delete(legacyKey)
    }
  })
}

/** 首 token 尚未到达时显示等待点（已有正文时不遮挡） */
const showStreamWaiting = computed(() => {
  if (!loading.value) return false
  const last = messages.value[messages.value.length - 1]
  if (last?.role !== 'assistant') return true
  if (last.content?.trim()) return false
  if (last.reasoning?.trim()) return false
  const idx = messages.value.length - 1
  if (hasActiveStep(resolveTimelineSteps(last, idx))) return false
  return true
})

const {
  messages, loading, send, resume, reconnectStream, stop,
  switchTo, ensureActive, getMessages, setMessages, migrateSession, destroySession,
} = useChatSessions(
  (sid: string, _chunk: string) => {
    const cid = chatStore.currentId ?? sid
    if (cid !== chatStore.currentId && sid !== chatStore.currentId) return
    const last = messages.value[messages.value.length - 1]
    if (last?.role !== 'assistant') return
    const apply = () => streamRenderer?.syncFromContent(last.content)
    if (!streamRenderer) {
      void ensureStreamRenderer().then(apply)
      return
    }
    apply()
  },
  (id: string) => {
    const cid = chatStore.currentId ?? id
    flushPersist(cid)
    chatStore.syncMessages(cid, getMessages(cid))
  },
  (id: string) => {
    const cid = chatStore.currentId ?? id
    schedulePersist(cid)
    chatStore.syncMessages(cid, getMessages(cid))
  },
  (sid: string, convId: string) => {
    if (convId !== sid) migrateSession(sid, convId)
    if (sid === chatStore.currentId || convId === chatStore.currentId) {
      chatStore.setConversationIdFromStream(convId)
      setMessages(convId, [...getMessages(convId)])
    }
  },
)

const inputText = ref('')
const inputRef = ref<InstanceType<typeof NInput>>()
const scrollRef = ref<HTMLElement | null>(null)
const copiedIndex = ref<number | null>(null)
let copyResetTimer: ReturnType<typeof setTimeout> | null = null
const persistTimers = new Map<string, ReturnType<typeof setTimeout>>()

function schedulePersist(sessionId: string) {
  const prev = persistTimers.get(sessionId)
  if (prev) clearTimeout(prev)
  persistTimers.set(sessionId, setTimeout(() => {
    persistTimers.delete(sessionId)
    chatStore.syncMessages(sessionId, getMessages(sessionId))
  }, 400))
}

function flushPersist(sessionId?: string | null) {
  if (sessionId) {
    const t = persistTimers.get(sessionId)
    if (t) clearTimeout(t)
    persistTimers.delete(sessionId)
    chatStore.syncMessages(sessionId, getMessages(sessionId))
    return
  }
  for (const [id, t] of persistTimers) {
    clearTimeout(t)
    chatStore.syncMessages(id, getMessages(id))
  }
  persistTimers.clear()
}

function canCopyAssistant(msg: { role: string; content: string }, idx: number): boolean {
  return msg.role === 'assistant'
    && !!msg.content.trim()
    && !(loading.value && idx === messages.value.length - 1)
}

async function copyAssistantMessage(text: string, idx: number) {
  if (!text.trim()) return
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  copiedIndex.value = idx
  if (copyResetTimer) clearTimeout(copyResetTimer)
  copyResetTimer = setTimeout(() => { copiedIndex.value = null }, 2000)
}

function scrollToBottom() {
  const el = scrollRef.value
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  const normalized = normalizeStreamingMarkdown(text)
  try { return md.render(normalized) } catch { return normalized.replace(/</g, '&lt;').replace(/>/g, '&gt;') }
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
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant' && last.content) {
    streamRenderer.syncFromContent(last.content)
  }
}

function canResume(msg: { role: string; status?: string; intent?: string }, idx: number): boolean {
  return msg.role === 'assistant'
    && !loading.value
    && idx === messages.value.length - 1
    && (msg.status === 'interrupted' || msg.status === 'failed')
    && msg.intent !== 'knowledge'
}

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  try {
    const convId = await chatStore.ensureCurrent()
    ensureActive(convId)

    if (messages.value.length === 0) {
      chatStore.updateTitle(convId, text)
    }

    inputText.value = ''
    settledHtml.value = ''
    sessionSettledHtml.delete(convId)
    streamRenderer?.clear()
    streamRenderer = null

    await nextTick()
    const sendPromise = send(text, convId)
    await nextTick()
    await ensureStreamRenderer()
    await sendPromise
    await nextTick()
    scrollToBottom()
  } catch (e) {
    console.error('[ChatView] 发送失败', e)
    inputText.value = text
  }
}

async function handleResume() {
  const last = messages.value[messages.value.length - 1]
  const convId = chatStore.currentId
  if (!last?.id || !convId || loading.value) return

  settledHtml.value = ''
  sessionSettledHtml.delete(convId)
  await nextTick()
  const resumePromise = resume(convId, last.id)
  await nextTick()
  await ensureStreamRenderer()
  await resumePromise
  await nextTick()
  scrollToBottom()
}

function markAssistantInterrupted(convId: string, messageId?: string) {
  const msgs = getMessages(convId)
  const target = messageId
    ? msgs.find(m => m.id === messageId && m.role === 'assistant')
    : msgs[msgs.length - 1]
  if (target?.role === 'assistant' && target.status !== 'completed') {
    target.status = 'interrupted'
  }
}

async function hydrateSessionFromStore(cid: string) {
  await chatStore.loadDetail(cid)
  const restored = chatStore.conversations.find(c => c.id === cid)?.messages ?? []
  if (!restored.length) {
    settledHtml.value = ''
    return
  }
  setMessages(cid, [...restored])
  migrateReasoningKeys()
  const lastAssistant = [...restored].reverse().find(m => m.role === 'assistant')
  if (lastAssistant?.content?.trim() && !loading.value) {
    settledHtml.value = captureSettledAssistantHtml(lastAssistant.content)
    sessionSettledHtml.set(cid, settledHtml.value)
  } else if (!loading.value) {
    settledHtml.value = sessionSettledHtml.get(cid) ?? ''
  }
  await nextTick()
  enhanceAllStaticMarkdown()
  scrollToBottom()
}

async function tryAutoReconnect(cid: string, active: ActiveGeneration) {
  try {
    const resp = await fetch(`/api/generations/${active.generationId}`, {
      headers: apiHeaders(),
    })

    if (resp.status === 410 || resp.status === 404) {
      clearActiveGeneration()
      markAssistantInterrupted(cid, active.messageId)
      await hydrateSessionFromStore(cid)
      return
    }

    if (!resp.ok) {
      await hydrateSessionFromStore(cid)
      return
    }

    const status = await resp.json() as { status: string; lastSeq: number }

    if (status.status === 'INTERRUPTED' || status.status === 'FAILED') {
      clearActiveGeneration()
      markAssistantInterrupted(cid, active.messageId)
      await hydrateSessionFromStore(cid)
      return
    }

    if (status.status === 'COMPLETED') {
      clearActiveGeneration()
      await hydrateSessionFromStore(cid)
      return
    }

    if (status.status === 'RUNNING') {
      await nextTick()
      const reconnectPromise = reconnectStream(active.generationId, active.lastSeq, cid)
      await nextTick()
      await ensureStreamRenderer()
      await reconnectPromise
      await hydrateSessionFromStore(cid)
    }
  } catch (e) {
    console.error('[ChatView] auto reconnect failed', e)
    clearActiveGeneration()
    await hydrateSessionFromStore(cid)
  }
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

onMounted(async () => {
  sessionHydrating = true
  try {
    await chatStore.init()

    const active = loadActiveGeneration()
    let cid: string
    if (active?.conversationId) {
      try {
        await chatStore.ensureConversation(active.conversationId)
        cid = active.conversationId
      } catch {
        cid = await chatStore.ensureCurrent()
      }
    } else {
      cid = await chatStore.ensureCurrent()
    }

    await chatStore.switchTo(cid)
    ensureActive(cid)
    await hydrateSessionFromStore(cid)

    if (active && active.conversationId === cid) {
      await tryAutoReconnect(cid, active)
      await hydrateSessionFromStore(cid)
    }
  } finally {
    sessionHydrating = false
  }

  inputRef.value?.focus()
  window.addEventListener('pagehide', onPageHide)
})

function onPageHide() {
  flushPersist()
  for (const conv of chatStore.conversations) {
    const msgs = getMessages(conv.id)
    if (msgs.length) chatStore.syncMessages(conv.id, msgs)
  }
}

onUnmounted(() => {
  window.removeEventListener('pagehide', onPageHide)
})

onUpdated(() => {
  nextTick(() => enhanceAllStaticMarkdown())
})

watch(theme, () => nextTick(() => reRenderStaticMermaids()))

watch(() => chatStore.currentId, async (newId, oldId) => {
  if (sessionHydrating || newId === oldId) return

  if (oldId) {
    chatStore.syncMessages(oldId, getMessages(oldId))
    if (settledHtml.value) sessionSettledHtml.set(oldId, settledHtml.value)
    if (!chatStore.conversations.some(c => c.id === oldId)) {
      destroySession(oldId)
      sessionSettledHtml.delete(oldId)
    }
  }

  clearReasoningUiState()
  streamRenderer?.clear()
  streamRenderer = null
  settledHtml.value = ''

  if (!newId) return

  ensureActive(newId)
  await hydrateSessionFromStore(newId)

  await nextTick()
  if (loading.value) void ensureStreamRenderer()
}, { flush: 'post' })

watch(
  () => {
    const last = messages.value[messages.value.length - 1]
    if (last?.role !== 'assistant') return 0
    return (last.content?.length ?? 0) + (last.reasoning?.length ?? 0)
  },
  async () => { await nextTick(); scrollToBottom() },
)

watch(
  () => messages.value.map(m => m.id ?? '').join('\0'),
  () => migrateReasoningKeys(),
)

function captureSettledAssistantHtml(content: string): string {
  return renderMarkdown(content)
}

function renderAssistantHtml(msg: { content?: string }, idx: number): string {
  if (idx === messages.value.length - 1 && settledHtml.value && !loading.value) {
    return settledHtml.value
  }
  return renderMarkdown(msg.content || '')
}

function enhanceAllStaticMarkdown(): void {
  document.querySelectorAll('.msg-md:not(.streaming)').forEach(el => {
    if (el instanceof HTMLElement) enhanceStaticMarkdown(el)
  })
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
      settledHtml.value = captureSettledAssistantHtml(last.content)
      if (chatStore.currentId) sessionSettledHtml.set(chatStore.currentId, settledHtml.value)
    } else {
      settledHtml.value = ''
    }
    streamRenderer = null
    nextTick(() => enhanceAllStaticMarkdown())
  }
}, { flush: 'sync' })
</script>

<template>
  <div class="chat-page">
    <!-- 全宽会话头（豆包式） -->
    <header class="chat-header">
      <button
        type="button"
        class="sidebar-toggle"
        :title="sidebarVisible ? '隐藏侧栏' : '显示侧栏'"
        @click="toggleSidebar"
      >
        <svg v-if="sidebarVisible" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <line x1="9" y1="3" x2="9" y2="21" />
          <polyline points="14 8 11 12 14 16" />
        </svg>
        <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <line x1="9" y1="3" x2="9" y2="21" />
          <polyline points="10 8 13 12 10 16" />
        </svg>
      </button>
      <div class="header-inner">
        <h1 class="header-title">{{ sessionTitle }}</h1>
        <span v-if="loading" class="header-status">
          <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
          正在回复
        </span>
      </div>
      <button
        v-if="!sidebarVisible"
        type="button"
        class="header-theme-btn"
        :title="isDark ? '切换浅色模式' : '切换深色模式'"
        @click="toggleTheme"
      >
        <svg v-if="isDark" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <circle cx="12" cy="12" r="5" /><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
        </svg>
        <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
        </svg>
      </button>
    </header>

    <!-- 消息区 -->
    <div ref="scrollRef" class="chat-scroll">
      <div class="chat-inner">
        <div v-if="chatStore.initializing && messages.length === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="40" height="40" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="14" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
              <circle cx="24" cy="24" r="5" fill="currentColor" opacity="0.5" />
            </svg>
          </div>
          <h2 class="empty-title">正在加载对话…</h2>
        </div>
        <div v-else-if="messages.length === 0" class="empty-state">
          <div class="empty-icon">
            <svg width="40" height="40" viewBox="0 0 48 48" fill="none">
              <circle cx="24" cy="24" r="14" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
              <circle cx="24" cy="24" r="5" fill="currentColor" opacity="0.5" />
            </svg>
          </div>
          <h2 class="empty-title">有什么可以帮你的？</h2>
          <p class="empty-desc">基于 ReActAgent 与知识库，支持流式 Markdown 回复</p>
          <div class="hint-chips">
            <button class="hint-chip" @click="inputText='介绍一下 RAG 的原理'; handleSend()">RAG 原理</button>
            <button class="hint-chip" @click="inputText='考勤制度是什么？'; handleSend()">检索知识库</button>
            <button class="hint-chip" @click="inputText='写一段 Python 快速排序'; handleSend()">写代码</button>
          </div>
        </div>

        <div v-else class="msg-list">
          <div
            v-for="(msg, idx) in messages"
            :key="msg.id ?? `local-${idx}`"
            class="msg-block"
            :class="msg.role"
          >
            <!-- 用户消息：右对齐气泡 -->
            <div v-if="msg.role === 'user'" class="user-bubble">{{ msg.content }}</div>

            <!-- AI 消息：全宽左对齐 -->
            <div v-else class="assistant-body">
              <OperationStack
                v-if="showTimeline(msg, idx)"
                :steps="resolveTimelineSteps(msg, idx)"
                :live="isTimelineLive(msg, idx)"
              />
              <template v-if="loading && idx === messages.length - 1 && msg.status !== 'completed'">
                <div v-if="showStreamWaiting" class="stream-waiting-dots" aria-label="正在生成">
                  <span class="typing-dots"><span class="dot"/><span class="dot"/><span class="dot"/></span>
                </div>
                <div
                  v-if="streamingHasContent"
                  :ref="setStreamingMdRef"
                  class="msg-md streaming"
                />
              </template>
              <div
                v-else
                class="msg-md"
                v-html="renderAssistantHtml(msg, idx)"
              />
              <div v-if="canCopyAssistant(msg, idx)" class="msg-copy-bar">
                <button
                  type="button"
                  class="msg-copy-btn smd-toolbtn"
                  :title="copiedIndex === idx ? '已复制' : '复制'"
                  @click="copyAssistantMessage(msg.content, idx)"
                >
                  <svg v-if="copiedIndex === idx" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                    <polyline points="20 6 9 17 4 12" />
                  </svg>
                  <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                    <rect x="9" y="9" width="13" height="13" rx="2" />
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                  </svg>
                </button>
              </div>
              <div v-if="canResume(msg, idx)" class="msg-resume-bar">
                <button type="button" class="resume-btn" @click="handleResume">继续生成</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 悬浮输入区 -->
    <footer class="chat-composer">
      <div class="composer-inner">
        <!-- 流式输出中：简洁状态条，避免 disabled 输入框 -->
        <div v-if="loading" class="composer-box composer-box--streaming">
          <div class="streaming-status">
            <span class="streaming-pulse" />
            <span>AI 正在回复…</span>
          </div>
          <button type="button" class="composer-icon-btn stop" title="停止生成" @click="stop">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor"><rect x="3" y="3" width="10" height="10" rx="1.5"/></svg>
          </button>
        </div>
        <!-- 就绪：正常输入 -->
        <div v-else class="composer-box">
          <NInput
            ref="inputRef"
            v-model:value="inputText"
            type="textarea"
            placeholder="发消息，Enter 发送"
            :autosize="{ minRows: 1, maxRows: 6 }"
            @keydown="handleKeydown"
            class="composer-input"
          />
          <button
            type="button"
            class="composer-icon-btn send"
            :disabled="!inputText.trim()"
            title="发送"
            @click="handleSend"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2 8l12-6-6 12-2-6-4-0z" fill="currentColor"/></svg>
          </button>
        </div>
        <p class="composer-hint">AI 生成内容仅供参考，请核实重要信息</p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  min-height: 0;
  position: relative;
  background: var(--sun-black);
}

/* ── 全宽会话头 ── */
.chat-header {
  flex-shrink: 0;
  width: 100%;
  height: 48px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
  z-index: 10;
  display: flex;
  align-items: center;
  padding: 0 12px 0 8px;
  gap: 4px;
}

.sidebar-toggle {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.sidebar-toggle:hover {
  background: var(--sun-surface-hover);
  color: var(--sun-text);
}

.header-theme-btn {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.header-theme-btn:hover {
  background: var(--sun-surface-hover);
  color: var(--sun-text);
}

.header-inner {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  position: relative;
  padding: 0 8px;
}

.header-title {
  font-size: var(--sun-font-lg);
  font-weight: 600;
  color: var(--sun-text);
  margin: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: min(480px, 70vw);
  text-align: center;
}

.header-status {
  position: absolute;
  right: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

/* ── 滚动消息区 ── */
.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.chat-inner {
  max-width: 820px;
  margin: 0 auto;
  padding: 24px 24px 140px;
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

/* ── 空状态 ── */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 48px 0 80px;
}

.empty-icon {
  color: var(--sun-text-muted);
  margin-bottom: 20px;
  opacity: 0.7;
}

.empty-title {
  font-size: var(--sun-font-2xl);
  font-weight: 600;
  color: var(--sun-text);
  margin: 0 0 8px;
  letter-spacing: -0.4px;
}

.empty-desc {
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  margin: 0 0 28px;
  line-height: var(--sun-line-relaxed);
}

.hint-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  max-width: 520px;
}

.hint-chip {
  padding: 8px 16px;
  background: var(--sun-surface);
  border: 1px solid var(--sun-border);
  border-radius: 20px;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-base);
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s, color 0.2s;
  font-family: inherit;
}

.hint-chip:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
  background: var(--sun-surface-hover);
}

/* ── 消息列表 ── */
.msg-list {
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding-bottom: 16px;
}

.msg-block.user {
  display: flex;
  justify-content: flex-end;
}

.user-bubble {
  max-width: 75%;
  padding: 10px 16px;
  background: var(--sun-surface);
  border: none;
  border-radius: 20px;
  font-size: var(--sun-font-md);
  line-height: var(--sun-line-relaxed);
  color: var(--sun-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-block.assistant {
  width: 100%;
}

.assistant-body {
  width: 100%;
  min-width: 0;
}

.stream-waiting-dots {
  padding: 8px 0 12px;
  min-height: 28px;
  display: flex;
  align-items: center;
}

.msg-copy-bar {
  margin-top: 10px;
  display: flex;
  justify-content: flex-start;
}

.msg-resume-bar {
  margin-top: 8px;
}

.resume-btn {
  font-size: var(--sun-font-base);
  padding: 4px 12px;
  border-radius: 8px;
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s, color 0.15s;
}

.resume-btn:hover {
  background: var(--sun-accent-muted);
  color: var(--sun-text);
  border-color: var(--sun-border-light);
}

.msg-copy-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  font-family: inherit;
  transition: background 0.15s, color 0.15s;
  line-height: 0;
}

.msg-copy-btn:hover {
  background: var(--sun-surface-hover);
  color: var(--sun-text);
}

.msg-copy-btn svg {
  flex-shrink: 0;
}

/* ── 悬浮输入区 ── */
.chat-composer {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 20;
  padding: 0 24px 20px;
  background: linear-gradient(to bottom, transparent 0%, var(--sun-black) 32%);
  pointer-events: none;
}

.composer-inner {
  max-width: 720px;
  margin: 0 auto;
  pointer-events: auto;
}

.composer-box {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--sun-deep);
  border: 1px solid var(--sun-border);
  border-radius: 20px;
  padding: 8px 10px 8px 18px;
  min-height: 48px;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-shadow: var(--composer-shadow);
}

.composer-box:focus-within {
  border-color: var(--sun-border-light);
  box-shadow: var(--composer-shadow-focus);
}

.composer-box--streaming {
  padding: 10px 12px 10px 18px;
  cursor: default;
}

.streaming-status {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  user-select: none;
}

.streaming-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--sun-text-muted);
  flex-shrink: 0;
  animation: glow-pulse 1.5s ease-in-out infinite;
}

.composer-input {
  flex: 1;
  min-width: 0;
  --n-font-size: var(--sun-font-md) !important;
  --n-border: none !important;
  --n-border-hover: none !important;
  --n-border-focus: none !important;
  --n-box-shadow-focus: none !important;
  --n-color: transparent !important;
  --n-color-focus: transparent !important;
  --n-color-disabled: transparent !important;
  --n-padding-vertical: 4px !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
}

.composer-icon-btn {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s, opacity 0.15s;
}

.composer-icon-btn.send {
  background: var(--sun-accent);
  color: var(--btn-primary-text);
}

.composer-icon-btn.send:hover:not(:disabled) {
  background: var(--sun-accent-hover);
}

.composer-icon-btn.send:disabled {
  background: var(--sun-border);
  color: var(--sun-text-muted);
  cursor: not-allowed;
  opacity: 0.7;
}

.composer-icon-btn.stop {
  background: transparent;
  border: 1px solid var(--sun-border);
  color: var(--sun-text-secondary);
}

.composer-icon-btn.stop:hover {
  border-color: var(--sun-red);
  color: var(--sun-red);
  background: rgba(248, 113, 113, 0.08);
}

.composer-hint {
  margin: 8px 0 0;
  text-align: center;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}

/* Markdown */
.msg-md {
  font-size: var(--sun-font-md);
  line-height: var(--sun-line-loose);
  color: var(--sun-text);
  word-break: break-word;
}

.msg-md :deep(h1), .msg-md :deep(h2), .msg-md :deep(h3) {
  margin: 16px 0 8px;
  font-weight: 700;
  color: var(--sun-text);
}

.msg-md :deep(h3) { font-size: var(--sun-font-md); }
.msg-md :deep(h2) { font-size: var(--sun-font-lg); }
.msg-md :deep(h1) { font-size: var(--sun-font-xl); }
.msg-md :deep(p) { margin: 6px 0; }
.msg-md :deep(strong) { color: var(--sun-text); font-weight: 600; }
.msg-md :deep(a) { color: var(--sun-blue); }
.msg-md :deep(ul), .msg-md :deep(ol) { margin: 8px 0; padding-left: 22px; }
.msg-md :deep(li) { margin: 3px 0; }
.msg-md :deep(hr) { border: none; border-top: 1px solid var(--sun-border); margin: 16px 0; }
.msg-md :deep(blockquote) {
  border-left: 3px solid var(--sun-border-light);
  padding: 4px 14px;
  margin: 12px 0;
  color: var(--sun-text-secondary);
}
.msg-md :deep(code) {
  background: var(--sun-accent-muted);
  color: var(--sun-text);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'JetBrains Mono', monospace;
  font-size: var(--sun-font-sm);
}
.msg-md :deep(pre:not(.smd-mermaid-source)) {
  background: var(--sun-deep);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 14px 18px;
  overflow-x: auto;
  margin: 12px 0;
}
.msg-md :deep(.smd-mermaid-wrapper .smd-mermaid-source) {
  background: transparent;
  border: none;
  border-radius: 0;
  box-shadow: none;
  margin: 0;
  padding: 14px 16px 16px;
}
.msg-md :deep(pre code.hljs) {
  background: none;
  padding: 0;
  font-size: var(--sun-font-sm);
  line-height: var(--sun-line);
}
.msg-md :deep(pre code:not(.hljs)) {
  background: none;
  color: var(--sun-text);
  padding: 0;
}
.msg-md :deep(pre.hljs),
.msg-md :deep(pre code.hljs) {
  background: var(--sun-deep);
}
.msg-md :deep(table) { border-collapse: collapse; margin: 12px 0; width: 100%; }
.msg-md :deep(th), .msg-md :deep(td) { border: 1px solid var(--sun-border); padding: 8px 14px; text-align: left; }
.msg-md :deep(th) { background: var(--sun-deep); font-weight: 600; }
.msg-md :deep(img) { max-width: 100%; border-radius: 8px; }
.msg-md :deep(.katex-display) {
  margin: 14px 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 4px 0;
}
.msg-md :deep(.katex) { font-size: 1.05em; }
.msg-md :deep(.katex-display > .katex) { font-size: 1.15em; }
/* 流式过程中隐藏 KaTeX 解析失败红字，结束后再完整渲染 */
.msg-md.streaming :deep(.katex-error) { display: none !important; }
.msg-md :deep(.mermaid-container) {
  display: flex;
  justify-content: center;
  margin: 16px 0;
  padding: 16px;
  background: var(--sun-deep);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow-x: auto;
}
</style>
