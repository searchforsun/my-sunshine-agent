/**
 * 对话历史 Pinia Store — 后端 API 为主存储，localStorage 仅存 currentId
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ChatMessage } from '../api/chat'
import {
  listConversations,
  createConversation,
  getConversation,
  deleteConversation,
  type ConversationSummary,
} from '../api/conversations'
import { sanitizeRestoredMessages } from '../api/streamError'

export interface Conversation {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: ChatMessage[]
}

const CURRENT_ID_KEY = 'sunshine-current-conversation-id'

function generateLocalId(): string {
  return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9)
}

function createLocalConversation(): Conversation {
  const now = Date.now()
  return {
    id: generateLocalId(),
    title: '新对话',
    createdAt: now,
    updatedAt: now,
    messages: [],
  }
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const currentId = ref<string | null>(null)
  const initializing = ref(false)
  let loaded = false

  function persistCurrentId() {
    if (currentId.value) {
      localStorage.setItem(CURRENT_ID_KEY, currentId.value)
    } else {
      localStorage.removeItem(CURRENT_ID_KEY)
    }
  }

  async function init() {
    if (loaded || initializing.value) return
    initializing.value = true
    try {
      const list = await listConversations()
      conversations.value = list.map(c => ({
        id: c.id,
        title: c.title,
        createdAt: c.createdAt,
        updatedAt: c.updatedAt,
        messages: [],
      }))

      const savedId = localStorage.getItem(CURRENT_ID_KEY)
      if (savedId && conversations.value.some(c => c.id === savedId)) {
        currentId.value = savedId
        await loadDetail(savedId)
      } else if (conversations.value.length > 0) {
        currentId.value = conversations.value[0].id
        await loadDetail(conversations.value[0].id)
      }
      loaded = true
    } catch (e) {
      console.warn('[chatStore] 后端加载失败，使用空列表', e)
      loaded = true
    } finally {
      initializing.value = false
    }
  }

  async function loadDetail(id: string) {
    try {
      const detail = await getConversation(id)
      const conv = conversations.value.find(c => c.id === id)
      if (conv) {
        conv.title = detail.title
        conv.messages = sanitizeRestoredMessages(detail.messages.map(m => ({
          id: m.id,
          role: m.role,
          content: m.content,
          status: m.status as ChatMessage['status'],
          intent: m.intent,
        })))
        conv.updatedAt = detail.updatedAt
      }
    } catch (e) {
      console.warn('[chatStore] 加载会话详情失败', id, e)
    }
  }

  const current = computed(() =>
    conversations.value.find(c => c.id === currentId.value) ?? null
  )

  const sortedConversations = computed(() =>
    [...conversations.value].sort((a, b) => b.updatedAt - a.updatedAt)
  )

  async function create(): Promise<string> {
    try {
      const created = await createConversation()
      const conv: Conversation = {
        id: created.id,
        title: created.title,
        createdAt: created.createdAt,
        updatedAt: created.updatedAt,
        messages: [],
      }
      conversations.value.unshift(conv)
      currentId.value = conv.id
      persistCurrentId()
      return conv.id
    } catch (e) {
      console.warn('[chatStore] 后端创建失败，使用本地会话', e)
      const conv = createLocalConversation()
      conversations.value.unshift(conv)
      currentId.value = conv.id
      persistCurrentId()
      return conv.id
    }
  }

  async function remove(id: string) {
    await deleteConversation(id)
    const idx = conversations.value.findIndex(c => c.id === id)
    if (idx === -1) return
    conversations.value.splice(idx, 1)
    if (currentId.value === id) {
      currentId.value = conversations.value[0]?.id ?? null
      persistCurrentId()
      if (currentId.value) await loadDetail(currentId.value)
    }
  }

  async function switchTo(id: string) {
    if (!conversations.value.some(c => c.id === id)) return
    currentId.value = id
    persistCurrentId()
    const conv = conversations.value.find(c => c.id === id)
    if (conv && conv.messages.length === 0) {
      await loadDetail(id)
    }
  }

  function updateTitleLocal(id: string, title: string) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv || conv.title !== '新对话') return
    conv.title = title.length > 28 ? title.slice(0, 28) + '…' : title || '新对话'
  }

  function syncMessages(id: string, msgs: ChatMessage[]) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    conv.messages = msgs
    conv.updatedAt = Date.now()
  }

  async function ensureCurrent(): Promise<string> {
    await init()
    if (!currentId.value || !conversations.value.some(c => c.id === currentId.value)) {
      return create()
    }
    return currentId.value
  }

  function setConversationIdFromStream(id: string) {
    if (conversations.value.some(c => c.id === id)) return
    conversations.value.unshift({
      id,
      title: '新对话',
      createdAt: Date.now(),
      updatedAt: Date.now(),
      messages: [],
    })
    currentId.value = id
    persistCurrentId()
  }

  return {
    conversations, currentId, current, sortedConversations, initializing,
    init, create, remove, switchTo, updateTitle: updateTitleLocal,
    syncMessages, ensureCurrent, loadDetail, setConversationIdFromStream,
  }
})
