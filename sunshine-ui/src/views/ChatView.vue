<script setup lang="ts">
import { ref, nextTick, watch, onMounted } from 'vue'
import { useChat } from '../api/chat'
import { NInput, NButton, NScrollbar, NAvatar, NSpace, NTag } from 'naive-ui'

const { messages, loading, send, stop, clear } = useChat()
const inputText = ref('')
const chatBody = ref<InstanceType<typeof NScrollbar>>()
const inputRef = ref<InstanceType<typeof NInput>>()

async function handleSend() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  inputText.value = ''
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

// Auto-focus input on mount
onMounted(() => {
  inputRef.value?.focus()
})

// Auto-scroll when new messages arrive
watch(() => messages.value.length, async () => {
  await nextTick()
  chatBody.value?.scrollTo({ top: 999999, behavior: 'smooth' })
})
</script>

<template>
  <div class="chat-root">
    <!-- Header -->
    <header class="chat-header">
      <div>
        <h2 class="chat-title">AI Assistant</h2>
        <p class="chat-subtitle">ReActAgent · Knowledge-base augmented</p>
      </div>
      <NButton
        text
        size="small"
        @click="clear"
        :disabled="messages.length === 0"
        class="clear-btn"
      >
        Clear conversation
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
        <h3>Start a conversation</h3>
        <p>Ask anything — your AI assistant with knowledge base access is ready.</p>
        <div class="hint-chips">
          <button class="hint-chip" @click="inputText='介绍一下你的能力'; handleSend()">
            What can you do?
          </button>
          <button class="hint-chip" @click="inputText='考勤制度是什么？'; handleSend()">
            Search knowledge base
          </button>
        </div>
      </div>

      <div class="msg-list">
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="msg-row"
          :class="msg.role"
          :style="{ animationDelay: idx === messages.length - 1 ? '0s' : '0s' }"
        >
          <div class="msg-avatar">
            <NAvatar :size="34" :style="{
              background: msg.role === 'user'
                ? 'linear-gradient(135deg, #3b82f6, #1d4ed8)'
                : 'linear-gradient(135deg, #f59e0b, #d97706)'
            }">
              {{ msg.role === 'user' ? 'U' : 'AI' }}
            </NAvatar>
          </div>
          <div class="msg-bubble">
            <div class="msg-meta">
              <span class="msg-sender">{{ msg.role === 'user' ? 'You' : 'Sunshine AI' }}</span>
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
            <div class="msg-text" v-text="msg.content || (loading && idx === messages.length - 1 ? 'Thinking...' : '')" />
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
          placeholder="Type your message... (Enter to send, Shift+Enter for new line)"
          :autosize="{ minRows: 1, maxRows: 4 }"
          :disabled="loading"
          @keydown="handleKeydown"
          class="chat-input"
          round
          size="large"
        />
        <div class="input-actions">
          <span class="char-hint" v-if="loading">
            <span class="pulse-dot online" /> AI is responding...
          </span>
          <span class="char-hint" v-else>
            Ready
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
              Stop
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
              Send
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
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
}

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
