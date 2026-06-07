<script setup lang="ts">
import { ref, nextTick, watch, onMounted } from 'vue'
import { useChat } from '../api/chat'
import { NInput, NButton, NScrollbar, NAvatar, NSpace, NTag } from 'naive-ui'
import { marked } from 'marked'
import 'github-markdown-css/github-markdown-dark.css'

marked.setOptions({ breaks: true, gfm: true })

function renderMarkdown(text: string): string {
  if (!text) return ''
  return marked(text) as string
}

const { messages, loading, send, stop, clear } = useChat()
const inputText = ref('')
const chatBody = ref<InstanceType<typeof NScrollbar>>()
const inputRef = ref<InstanceType<typeof NInput>>()

// Typewriter: each assistant message tracks how many characters are revealed
const revealed = ref<Record<number, number>>({})

function displayedContent(msg: any, idx: number): string {
  if (msg.role !== 'assistant') return msg.content
  const max = msg.content.length
  const r = revealed.value[idx] ?? max
  if (r >= max) return msg.content
  return msg.content.substring(0, r)
}

// Reveal characters gradually for streaming effect
let typewriterTimer: ReturnType<typeof setInterval> | null = null

watch(() => messages.value.length, () => {
  const lastIdx = messages.value.length - 1
  const last = messages.value[lastIdx]
  if (!last || last.role !== 'assistant') return

  // When loading and content is received, start typewriter
  typewriterTimer = setInterval(() => {
    const max = last.content.length
    const current = revealed.value[lastIdx] ?? 0
    if (current >= max) {
      if (!loading.value && typewriterTimer) {
        clearInterval(typewriterTimer)
        typewriterTimer = null
      }
      return
    }
    // Reveal more chars — faster for longer content
    const speed = max > 500 ? 8 : max > 200 ? 4 : 2
    revealed.value[lastIdx] = Math.min(current + speed, max)
    revealed.value = { ...revealed.value }
  }, 30)
})

watch(loading, (v) => {
  if (!v && typewriterTimer) {
    // When loading stops, reveal all immediately
    const lastIdx = messages.value.length - 1
    if (lastIdx >= 0) {
      revealed.value[lastIdx] = messages.value[lastIdx].content.length
      revealed.value = { ...revealed.value }
    }
    clearInterval(typewriterTimer)
    typewriterTimer = null
  }
})

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  inputText.value = ''
  // Reset revealed for the upcoming assistant message
  const nextIdx = messages.value.length + 1 // user msg + assistant msg slots
  revealed.value[nextIdx] = 0
  await send(text)
  await nextTick()
  chatBody.value?.scrollTo({ top: 999999, behavior: 'smooth' })
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

onMounted(() => {
  inputRef.value?.focus()
})

// Auto-scroll when content changes
watch(() => {
  const lastIdx = messages.value.length - 1
  return lastIdx >= 0 ? revealed.value[lastIdx] : 0
}, async () => {
  await nextTick()
  chatBody.value?.scrollTo({ top: 999999, behavior: 'smooth' })
})
</script>

<template>
  <div class="chat-root">
    <!-- Header -->
    <header class="chat-header">
      <div>
        <h2 class="chat-title">AI 智能助手</h2>
        <p class="chat-subtitle">ReActAgent · 知识库增强</p>
      </div>
      <NButton
        text
        size="small"
        @click="clear"
        :disabled="messages.length === 0"
        class="clear-btn"
      >
        清空对话
      </NButton>
    </header>

    <!-- Messages -->
    <NScrollbar ref="chatBody" class="chat-body">
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
          <button class="hint-chip" @click="inputText='介绍一下你的能力'; handleSend()">
            你能做什么？
          </button>
          <button class="hint-chip" @click="inputText='考勤制度是什么？'; handleSend()">
            检索知识库
          </button>
        </div>
      </div>

      <div class="msg-list">
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="msg-row"
          :class="msg.role"
        >
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
                size="tiny"
                :bordered="false"
                type="warning"
                class="typing-tag"
              >
                <span class="typing-dots">
                  <span class="dot" />
                  <span class="dot" />
                  <span class="dot" />
                </span>
              </NTag>
            </div>
            <!-- User messages: plain text. Assistant messages: Markdown rendered -->
            <div
              v-if="msg.role === 'assistant'"
              class="markdown-body msg-md"
              v-html="renderMarkdown(displayedContent(msg, idx))"
            />
            <div
              v-else
              class="msg-text"
            >{{ displayedContent(msg, idx) || (loading && idx === messages.length - 1 ? '思考中...' : '') }}</div>
          </div>
        </div>
      </div>
    </NScrollbar>

    <!-- Input -->
    <footer class="chat-footer">
      <div class="input-wrapper">
        <NInput
          ref="inputRef"
          v-model:value="inputText"
          type="textarea"
          placeholder="输入消息（Enter 发送，Shift+Enter 换行）"
          :autosize="{ minRows: 1, maxRows: 4 }"
          :disabled="loading"
          @keydown="handleKeydown"
          class="chat-input"
          round
          size="large"
        />
        <div class="input-actions">
          <span class="char-hint" v-if="loading">
            <span class="pulse-dot online" /> AI 回复中...
          </span>
          <span class="char-hint" v-else>
            就绪
          </span>
          <NSpace>
            <NButton
              v-if="loading"
              @click="stop"
              type="error"
              size="small"
              secondary
              round
            >
              停止
            </NButton>
            <NButton
              @click="handleSend"
              type="warning"
              size="small"
              :disabled="!inputText.trim() || loading"
              round
            >
              <template #icon>
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                  <path d="M2 8l12-6-6 12-2-6-4-0z" fill="currentColor" />
                </svg>
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
.chat-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  max-width: 820px;
  margin: 0 auto;
  padding: 0 24px;
}

/* --- Header --- */
.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 20px 0 12px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.chat-title {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--sun-text);
  margin: 0;
}

.chat-subtitle {
  font-size: 12.5px;
  color: var(--sun-text-muted);
  margin: 2px 0 0;
}

.clear-btn {
  color: var(--sun-text-muted) !important;
  font-size: 12px;
}

/* --- Body --- */
.chat-body {
  flex: 1;
  min-height: 0;
}

/* --- Empty state --- */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 72px 24px;
  text-align: center;
}

.empty-glow {
  margin-bottom: 20px;
  animation: glow-pulse 3s ease-in-out infinite;
}

.empty-state h3 {
  font-size: 20px;
  font-weight: 600;
  color: var(--sun-text);
  margin-bottom: 6px;
}

.empty-state p {
  font-size: 14px;
  color: var(--sun-text-muted);
  max-width: 360px;
  line-height: 1.5;
}

.hint-chips {
  display: flex;
  gap: 8px;
  margin-top: 20px;
  flex-wrap: wrap;
  justify-content: center;
}

.hint-chip {
  padding: 7px 15px;
  background: var(--sun-surface);
  border: 1px solid var(--sun-border);
  border-radius: 20px;
  color: var(--sun-text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: all .2s;
  font-family: inherit;
}
.hint-chip:hover {
  border-color: var(--sun-amber);
  color: var(--sun-amber-light);
  background: var(--sun-amber-glow);
}

/* --- Messages --- */
.msg-list {
  padding: 16px 0 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.msg-row {
  display: flex;
  gap: 12px;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  animation: fade-in-up .35s var(--ease-out-expo) forwards;
  transition: background .2s;
}
.msg-row:hover {
  background: rgba(26, 35, 50, 0.4);
}

.msg-avatar {
  flex-shrink: 0;
  padding-top: 2px;
}

.msg-bubble {
  flex: 1;
  min-width: 0;
}

.msg-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.msg-sender {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-muted);
  text-transform: uppercase;
  letter-spacing: .5px;
}

.msg-text {
  font-size: 14.5px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
}

/* GitHub Markdown overrides for dark theme */
.msg-md {
  font-size: 14.5px;
  color: var(--sun-text);
  background: transparent !important;
}
.msg-md :deep(h1), .msg-md :deep(h2), .msg-md :deep(h3),
.msg-md :deep(h4), .msg-md :deep(h5), .msg-md :deep(h6) {
  color: var(--sun-text);
  border-bottom-color: var(--sun-border);
}
.msg-md :deep(strong) { color: var(--sun-amber-light); }
.msg-md :deep(a) { color: var(--sun-blue); }
.msg-md :deep(code) {
  background: rgba(245, 158, 11, 0.08);
  color: var(--sun-amber-light);
}
.msg-md :deep(pre) {
  background: var(--sun-deep);
  border: 1px solid var(--sun-border);
}
.msg-md :deep(blockquote) {
  color: var(--sun-text-secondary);
  border-left-color: var(--sun-amber);
}
.msg-md :deep(table) {
  border-color: var(--sun-border);
}
.msg-md :deep(th), .msg-md :deep(td) {
  border-color: var(--sun-border);
}
.msg-md :deep(hr) {
  border-color: var(--sun-border);
}
.msg-md :deep(li) { color: var(--sun-text); }

/* --- Typing dots --- */
.typing-tag {
  font-size: 10px !important;
}

.typing-dots {
  display: inline-flex;
  gap: 3px;
  align-items: center;
}
.typing-dots .dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--sun-amber);
  animation: dot-bounce 1.3s ease-in-out infinite;
}
.typing-dots .dot:nth-child(2) { animation-delay: .15s; }
.typing-dots .dot:nth-child(3) { animation-delay: .3s; }

/* --- Footer --- */
.chat-footer {
  flex-shrink: 0;
  padding: 12px 0 20px;
}

.input-wrapper {
  background: var(--sun-deep);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-xl);
  padding: 10px 14px 12px;
  transition: border-color .3s;
}
.input-wrapper:focus-within {
  border-color: var(--sun-amber);
  box-shadow: 0 0 0 3px var(--sun-amber-glow);
}

.chat-input {
  --n-border: none !important;
  --n-border-hover: none !important;
  --n-border-focus: none !important;
  --n-box-shadow-focus: none !important;
  --n-color: transparent !important;
  --n-color-focus: transparent !important;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--sun-border);
}

.char-hint {
  font-size: 11.5px;
  color: var(--sun-text-muted);
  display: flex;
  align-items: center;
  gap: 6px;
  font-family: 'JetBrains Mono', monospace;
}
</style>
