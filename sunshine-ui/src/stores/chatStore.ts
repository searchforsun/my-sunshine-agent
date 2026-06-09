/**
 * 对话历史 Pinia Store — localStorage 持久化
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatMessage } from '../api/chat'

export interface Conversation {
  id: string
  title: string
  createdAt: number
  messages: ChatMessage[]
}

const STORAGE_KEY = 'sunshine-conversations'

function generateId(): string {
  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9)
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const currentId = ref<string | null>(null)
  let loaded = false

  // ── 持久化 ──
  function persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        conversations: conversations.value,
        currentId: currentId.value,
      }))
    } catch { /* quota exceeded, ignore */ }
  }

  function load() {
    if (loaded) return
    loaded = true
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const data = JSON.parse(raw)
        conversations.value = data.conversations || []
        currentId.value = data.currentId || null
      }
    } catch { /* corrupted data, start fresh */ }
  }

  // ── 计算属性 ──
  const current = computed(() =>
    conversations.value.find(c => c.id === currentId.value) ?? null
  )

  const sortedConversations = computed(() =>
    [...conversations.value].sort((a, b) => b.createdAt - a.createdAt)
  )

  // ── 增删改查 ──
  function create(): string {
    const id = generateId()
    conversations.value.unshift({ id, title: '新对话', createdAt: Date.now(), messages: [] })
    currentId.value = id
    persist()
    return id
  }

  function remove(id: string) {
    const idx = conversations.value.findIndex(c => c.id === id)
    if (idx === -1) return
    conversations.value.splice(idx, 1)
    if (currentId.value === id) {
      currentId.value = conversations.value[0]?.id ?? null
    }
    persist()
  }

  function switchTo(id: string) {
    if (conversations.value.some(c => c.id === id)) {
      currentId.value = id
      persist()
    }
  }

  function updateTitle(id: string, firstMsg: string) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv || conv.title !== '新对话') return
    const title = firstMsg.replace(/[\n\r]/g, ' ').trim()
    conv.title = title.length > 28 ? title.slice(0, 28) + '…' : title || '新对话'
    persist()
  }

  function syncMessages(id: string, msgs: ChatMessage[]) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    conv.messages = msgs
    persist()
  }

  function ensureCurrent() {
    if (!currentId.value || !conversations.value.some(c => c.id === currentId.value)) {
      create()
    }
  }

  return {
    conversations, currentId, current, sortedConversations,
    load, create, remove, switchTo, updateTitle, syncMessages, ensureCurrent,
  }
})
