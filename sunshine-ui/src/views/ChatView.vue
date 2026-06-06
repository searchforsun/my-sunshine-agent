<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { useChat } from '../api/chat'
import { NInput, NButton, NSpace, NScrollbar, NTag, NAvatar } from 'naive-ui'

const { messages, loading, send, stop, clear } = useChat()
const inputText = ref('')
const chatContainer = ref<InstanceType<typeof NScrollbar>>()

async function handleSend() {
  const text = inputText.value.trim()
  if (!text) return
  inputText.value = ''
  await send(text)
  await nextTick()
  chatContainer.value?.scrollTo({ top: 999999, behavior: 'smooth' })
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100vh">
    <!-- 头部 -->
    <div style="padding: 16px 24px; border-bottom: 1px solid var(--n-border-color); display: flex; justify-content: space-between; align-items: center">
      <span style="font-size: 16px; font-weight: 600">AI 对话</span>
      <NSpace>
        <NButton size="small" @click="clear" :disabled="messages.length === 0">清空对话</NButton>
      </NSpace>
    </div>

    <!-- 消息列表 -->
    <NScrollbar ref="chatContainer" style="flex: 1; padding: 16px 24px">
      <div v-if="messages.length === 0" style="text-align: center; padding: 80px 0; color: var(--n-text-color-3)">
        <div style="font-size: 48px; margin-bottom: 16px">💬</div>
        <div style="font-size: 18px; margin-bottom: 8px">开始对话</div>
        <div>向 AI 助手提问，获取智能回复</div>
      </div>

      <div
        v-for="(msg, idx) in messages"
        :key="idx"
        style="margin-bottom: 20px; display: flex; gap: 12px; align-items: flex-start"
      >
        <NAvatar
          :style="{ background: msg.role === 'user' ? 'var(--n-color-target)' : 'var(--primary-color)' }"
          size="small"
        >
          {{ msg.role === 'user' ? '我' : 'AI' }}
        </NAvatar>
        <div style="flex: 1; min-width: 0">
          <div style="font-size: 12px; color: var(--n-text-color-3); margin-bottom: 4px">
            {{ msg.role === 'user' ? '我' : 'Sunshine AI' }}
            <NTag
              v-if="msg.role === 'assistant' && loading && idx === messages.length - 1"
              size="tiny" type="info" :bordered="false" style="margin-left: 8px"
            >
              回复中...
            </NTag>
          </div>
          <div style="white-space: pre-wrap; line-height: 1.6; word-break: break-word">
            {{ msg.content || (loading && idx === messages.length - 1 ? '思考中...' : '') }}
          </div>
        </div>
      </div>
    </NScrollbar>

    <!-- 输入区 -->
    <div style="padding: 16px 24px; border-top: 1px solid var(--n-border-color); background: var(--n-color)">
      <NSpace vertical style="width: 100%">
        <NInput
          v-model:value="inputText"
          type="textarea"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行..."
          :autosize="{ minRows: 1, maxRows: 4 }"
          :disabled="loading"
          @keydown="handleKeydown"
        />
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span style="font-size: 12px; color: var(--n-text-color-3)">
            {{ loading ? 'AI 正在回复...' : '已就绪' }}
          </span>
          <NSpace>
            <NButton v-if="loading" @click="stop" type="warning" size="small">停止</NButton>
            <NButton
              @click="handleSend"
              type="primary"
              :disabled="!inputText.trim() || loading"
              size="small"
            >
              {{ loading ? '回复中' : '发送' }}
            </NButton>
          </NSpace>
        </div>
      </NSpace>
    </div>
  </div>
</template>
