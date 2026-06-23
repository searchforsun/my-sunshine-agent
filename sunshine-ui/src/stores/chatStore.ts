/**
 * 对话历史 Pinia Store — 后端 API 为主存储，localStorage 缓存兜底（含 reasoning）
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from './authStore'
import type { ChatMessage } from '../api/chat'
import type { ConversationSummary, ConversationMessage } from '../api/conversations'
import {
  listConversations,
  createConversation,
  getConversation,
  deleteConversation,
  isValidConversationId,
} from '../api/conversations'
import { sanitizeRestoredMessages } from '../api/streamError'
import {
  cacheMessages,
  loadCachedIndex,
  loadCachedMessages,
  mergeRestoredMessages,
  removeCachedIndex,
  upsertCachedIndex,
} from '../api/conversationCache'

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

function mapApiMessages(messages: ConversationMessage[]): ChatMessage[] {
  return messages.map(m => ({
    id: m.id,
    role: m.role,
    content: m.content,
    reasoning: m.reasoning,
    steps: m.steps,
    status: m.status as ChatMessage['status'],
    intent: m.intent,
    executionPlanId: m.executionPlanId,
  }))
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const currentId = ref<string | null>(null)
  const initializing = ref(false)
  let loaded = false
  let initPromise: Promise<void> | null = null

  function persistCurrentId() {
    if (isValidConversationId(currentId.value)) {
      localStorage.setItem(CURRENT_ID_KEY, currentId.value)
    } else {
      localStorage.removeItem(CURRENT_ID_KEY)
    }
  }

  function mergeApiList(list: ConversationSummary[]): void {
    const prevById = new Map(conversations.value.map(c => [c.id, c]))
    const merged: Conversation[] = list.map(c => {
      const prev = prevById.get(c.id)
      upsertCachedIndex({
        id: c.id,
        title: c.title,
        createdAt: c.createdAt,
        updatedAt: c.updatedAt,
      })
      return {
        id: c.id,
        title: c.title,
        createdAt: c.createdAt,
        updatedAt: c.updatedAt,
        messages: prev?.messages ?? [],
      }
    })
    conversations.value = merged.filter(c => isValidConversationId(c.id))
  }

  /** 仅给已存在的会话补本地缓存消息，避免侧栏出现重复幽灵条目 */
  function enrichFromCache(): void {
    for (const meta of loadCachedIndex()) {
      const conv = conversations.value.find(c => c.id === meta.id)
      if (!conv) continue
      const cached = loadCachedMessages(meta.id)
      if (cached?.length && conv.messages.length === 0) {
        conv.messages = sanitizeRestoredMessages(cached)
      }
    }
  }

  function restoreCurrentFromSavedOrFirst(): void {
    const savedId = localStorage.getItem(CURRENT_ID_KEY)
    if (!isValidConversationId(savedId)) {
      localStorage.removeItem(CURRENT_ID_KEY)
    }
    if (isValidConversationId(savedId) && conversations.value.some(c => c.id === savedId)) {
      currentId.value = savedId
      persistCurrentId()
      return
    }
    if (isValidConversationId(savedId)) {
      const cachedMeta = loadCachedIndex().find(c => c.id === savedId)
      if (cachedMeta && !conversations.value.some(c => c.id === savedId)) {
        conversations.value.unshift({
          ...cachedMeta,
          messages: sanitizeRestoredMessages(loadCachedMessages(savedId) ?? []),
        })
      }
      if (conversations.value.some(c => c.id === savedId)) {
        currentId.value = savedId
        persistCurrentId()
        return
      }
    }
    if (conversations.value.length > 0) {
      currentId.value = conversations.value[0].id
      persistCurrentId()
    }
  }

  async function init(): Promise<void> {
    if (loaded) return
    if (initPromise) return initPromise

    initPromise = (async () => {
      initializing.value = true
      try {
        const auth = useAuthStore()
        if (!auth.initialized) {
          await auth.fetchMe()
        }
        if (!auth.isLoggedIn) {
          loaded = true
          return
        }
        const list = await listConversations()
        mergeApiList(list)
        restoreCurrentFromSavedOrFirst()
        if (isValidConversationId(currentId.value)) {
          await loadDetail(currentId.value)
        }
        enrichFromCache()
        loaded = true
      } catch (e) {
        console.warn('[chatStore] 后端加载失败，尝试 localStorage 缓存', e)
        const index = loadCachedIndex()
        if (index.length > 0) {
          conversations.value = index.map(c => ({
            id: c.id,
            title: c.title,
            createdAt: c.createdAt,
            updatedAt: c.updatedAt,
            messages: sanitizeRestoredMessages(loadCachedMessages(c.id) ?? []),
          }))
          restoreCurrentFromSavedOrFirst()
        }
        loaded = true
      } finally {
        initializing.value = false
      }
    })()

    return initPromise
  }

  async function loadDetail(id: string) {
    if (!isValidConversationId(id)) return
    try {
      const detail = await getConversation(id)
      const conv = conversations.value.find(c => c.id === id)
      if (conv) {
        conv.title = detail.title
        const apiMsgs = mapApiMessages(detail.messages)
        const cached = loadCachedMessages(id)
        conv.messages = sanitizeRestoredMessages(mergeRestoredMessages(apiMsgs, cached))
        conv.updatedAt = detail.updatedAt
        if (conv.messages.length) {
          cacheMessages(id, conv.messages, {
            title: conv.title,
            createdAt: conv.createdAt,
            updatedAt: conv.updatedAt,
          })
        }
      }
    } catch (e) {
      console.warn('[chatStore] 加载会话详情失败，尝试本地缓存', id, e)
      const conv = conversations.value.find(c => c.id === id)
      const cached = loadCachedMessages(id)
      if (conv && cached?.length) {
        conv.messages = sanitizeRestoredMessages(cached)
      }
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
      upsertCachedIndex({
        id: conv.id,
        title: conv.title,
        createdAt: conv.createdAt,
        updatedAt: conv.updatedAt,
      })
      return conv.id
    } catch (e) {
      console.warn('[chatStore] 后端创建失败，使用本地会话', e)
      const conv = createLocalConversation()
      conversations.value.unshift(conv)
      currentId.value = conv.id
      persistCurrentId()
      upsertCachedIndex({
        id: conv.id,
        title: conv.title,
        createdAt: conv.createdAt,
        updatedAt: conv.updatedAt,
      })
      return conv.id
    }
  }

  async function remove(id: string) {
    try {
      await deleteConversation(id)
    } catch (e) {
      console.warn('[chatStore] 后端删除失败', id, e)
    }
    removeCachedIndex(id)
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
    await loadDetail(id)
  }

  function updateTitleLocal(id: string, title: string) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv || conv.title !== '新对话') return
    conv.title = title.length > 28 ? title.slice(0, 28) + '…' : title || '新对话'
    upsertCachedIndex({
      id: conv.id,
      title: conv.title,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
    })
  }

  function syncMessages(id: string, msgs: ChatMessage[]) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    conv.messages = msgs
    if (msgs.length) {
      cacheMessages(id, msgs, {
        title: conv.title,
        createdAt: conv.createdAt,
        updatedAt: conv.updatedAt,
      })
    }
  }

  async function ensureConversation(id: string): Promise<void> {
    if (!conversations.value.some(c => c.id === id)) {
      try {
        const detail = await getConversation(id)
        const apiMsgs = mapApiMessages(detail.messages)
        const cached = loadCachedMessages(id)
        conversations.value.unshift({
          id: detail.id,
          title: detail.title,
          createdAt: detail.createdAt,
          updatedAt: detail.updatedAt,
          messages: sanitizeRestoredMessages(mergeRestoredMessages(apiMsgs, cached)),
        })
      } catch (e) {
        const cached = loadCachedMessages(id)
        const meta = loadCachedIndex().find(c => c.id === id)
        if (cached?.length || meta) {
          conversations.value.unshift({
            id,
            title: meta?.title ?? '新对话',
            createdAt: meta?.createdAt ?? Date.now(),
            updatedAt: meta?.updatedAt ?? Date.now(),
            messages: sanitizeRestoredMessages(cached ?? []),
          })
        } else {
          console.warn('[chatStore] 无法加载 active 会话', id, e)
          throw e
        }
      }
    }
    await switchTo(id)
  }

  async function ensureCurrent(): Promise<string> {
    await init()
    if (!isValidConversationId(currentId.value) || !conversations.value.some(c => c.id === currentId.value)) {
      return create()
    }
    return currentId.value
  }

  function setConversationIdFromStream(newId: string) {
    if (!isValidConversationId(newId)) return
    const oldId = currentId.value
    if (oldId === newId) return

    const oldConv = oldId ? conversations.value.find(c => c.id === oldId) : undefined
    const existing = conversations.value.find(c => c.id === newId)

    if (existing) {
      if (oldConv?.messages.length) {
        existing.messages = oldConv.messages
        existing.title = oldConv.title !== '新对话' ? oldConv.title : existing.title
        cacheMessages(newId, existing.messages, existing)
      }
    } else {
      conversations.value.unshift({
        id: newId,
        title: oldConv?.title ?? '新对话',
        createdAt: oldConv?.createdAt ?? Date.now(),
        updatedAt: Date.now(),
        messages: oldConv?.messages ?? [],
      })
      if (oldConv?.messages.length) {
        cacheMessages(newId, oldConv.messages, oldConv)
      }
    }

    if (oldId && oldId !== newId) {
      conversations.value = conversations.value.filter(c => c.id !== oldId)
      removeCachedIndex(oldId)
    }

    currentId.value = newId
    persistCurrentId()
    upsertCachedIndex({
      id: newId,
      title: conversations.value.find(c => c.id === newId)?.title ?? '新对话',
      createdAt: oldConv?.createdAt ?? Date.now(),
      updatedAt: Date.now(),
    })
  }

  return {
    conversations, currentId, current, sortedConversations, initializing,
    init, create, remove, switchTo, ensureConversation, updateTitle: updateTitleLocal,
    syncMessages, ensureCurrent, loadDetail, setConversationIdFromStream,
  }
})
