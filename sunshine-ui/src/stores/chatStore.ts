/**
 * 对话历史 Pinia Store — 后端 API 为主存储，localStorage 缓存兜底（含 reasoning）
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from './authStore'
import type { ChatMessage } from '../api/chat'
import type { ConversationSummary, ConversationMessage } from '../api/conversations'
import type { ExecutionPreference } from '../api/executionModes'
import {
  listConversations,
  createConversation,
  getConversation,
  getConversationMessages,
  deleteConversation,
  updateConversationTitle,
  updateConversationCheckout,
  isValidConversationId,
} from '../api/conversations'
import { isConversationNotFoundError } from '../api/apiError'
import { hydrateStreamError, isLikelyStreamFailureContent, sanitizeRestoredMessages } from '../api/streamError'
import {
  cacheMessages,
  loadCachedIndex,
  loadCachedMessages,
  mergeRestoredMessages,
  removeCachedIndex,
  upsertCachedIndex,
} from '../api/conversationCache'
import { hydratePlanAnswerFromContent, normalizeRestoredInterleavedContent, sanitizePlanAssistantMessage } from '../api/contentInterleave'
import { ensurePlanTimelineSteps } from '../api/planHydrate'
import { hydrateTimelineBoundsFromMessageTimes } from '../api/timelineMessageClock'

export interface Conversation {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: ChatMessage[]
  executionPreference?: ExecutionPreference
  kbId?: string | null
  modelName?: string | null
  kind?: string
  workspaceId?: string | null
  checkoutPath?: string | null
}

const CURRENT_ID_KEY = 'sunshine-current-conversation-id'
const DEFAULT_CONV_TITLE = '新对话'

/** 每会话历史加载状态：hasMore 表示更早消息仍存在；loading 防并发 */
const historyHasMore = new Map<string, boolean>()
const historyLoading = new Set<string>()

/** API 仍为默认标题时保留本地已推导标题，避免流式过程中 loadDetail / 侧栏点击回退 */
function pickConversationTitle(apiTitle: string, localTitle?: string): string {
  if (apiTitle && apiTitle !== DEFAULT_CONV_TITLE) return apiTitle
  if (localTitle && localTitle !== DEFAULT_CONV_TITLE) return localTitle
  return apiTitle || localTitle || DEFAULT_CONV_TITLE
}

function mapApiMessages(messages: ConversationMessage[]): ChatMessage[] {
  return messages.map(m => {
    const msg: ChatMessage = {
      id: m.id,
      role: m.role,
      content: m.content,
      reasoning: m.reasoning,
      steps: m.steps,
      contentBlocks: m.contentBlocks,
      status: m.status as ChatMessage['status'],
      intent: m.intent,
      executionPlanId: m.executionPlanId,
      executionPreference: m.executionPreference,
      createdAt: m.createdAt,
      updatedAt: m.updatedAt,
      seq: m.seq,
    }
    if (msg.role === 'assistant') {
      sanitizePlanAssistantMessage(msg)
      hydratePlanAnswerFromContent(msg)
      normalizeRestoredInterleavedContent(msg)
      hydrateTimelineBoundsFromMessageTimes(msg)
      if (!msg.steps?.length && msg.executionPlanId) {
        msg.steps = ensurePlanTimelineSteps(msg)
      }
    }
    if (msg.role === 'assistant' && (msg.status === 'failed' || isLikelyStreamFailureContent(msg.content))) {
      hydrateStreamError(msg)
    }
    return msg
  })
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const currentId = ref<string | null>(null)
  const initializing = ref(false)
  /** workspace selector → chart page：在对话页勾选分支后再创建 */
  const pendingWorkspace = ref<{ wsId: string; wsName: string; wsBranch?: string } | null>(null)
  /** 顶部"新任务"入口：空白页 + 项目选择器，未选项目前不创建会话 */
  const newTaskMode = ref(false)
  let loaded = false
  let initPromise: Promise<void> | null = null

  function persistCurrentId() {
    if (isValidConversationId(currentId.value)) {
      localStorage.setItem(CURRENT_ID_KEY, currentId.value)
    } else {
      localStorage.removeItem(CURRENT_ID_KEY)
    }
  }

  function purgeStaleConversation(id: string): void {
    removeCachedIndex(id)
    const idx = conversations.value.findIndex(c => c.id === id)
    if (idx !== -1) conversations.value.splice(idx, 1)
    if (currentId.value === id) {
      currentId.value = conversations.value[0]?.id ?? null
      persistCurrentId()
    }
  }

  function mergeApiList(list: ConversationSummary[]): void {
    const prevById = new Map(conversations.value.map(c => [c.id, c]))
    const cachedIndex = loadCachedIndex()
    const merged: Conversation[] = list.map(c => {
      const prev = prevById.get(c.id)
      const cachedMeta = cachedIndex.find(m => m.id === c.id)
      const title = pickConversationTitle(c.title, prev?.title ?? cachedMeta?.title)
      upsertCachedIndex({
        id: c.id,
        title,
        createdAt: c.createdAt,
        updatedAt: c.updatedAt,
      })
      return {
        id: c.id,
        title,
        createdAt: c.createdAt,
        updatedAt: c.updatedAt,
        messages: prev?.messages ?? [],
        executionPreference: c.executionPreference ?? prev?.executionPreference,
        kbId: c.kbId ?? prev?.kbId ?? null,
        modelName: c.modelName ?? prev?.modelName ?? null,
        kind: c.kind ?? prev?.kind,
        workspaceId: c.workspaceId ?? prev?.workspaceId ?? null,
        checkoutPath: c.checkoutPath ?? prev?.checkoutPath ?? null,
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

  async function restoreCurrentFromSavedOrFirst(): Promise<void> {
    const savedId = localStorage.getItem(CURRENT_ID_KEY)
    if (!isValidConversationId(savedId)) {
      localStorage.removeItem(CURRENT_ID_KEY)
    }
    if (isValidConversationId(savedId) && conversations.value.some(c => c.id === savedId)) {
      currentId.value = savedId
      persistCurrentId()
      return
    }
    // 仅在后端确认会话仍存在时恢复 savedId，避免清库后 localStorage 幽灵会话导致 stream 404
    if (isValidConversationId(savedId) && !conversations.value.some(c => c.id === savedId)) {
      try {
        const detail = await getConversation(savedId)
        const cached = loadCachedMessages(savedId)
        const cachedMeta = loadCachedIndex().find(c => c.id === savedId)
        conversations.value.unshift({
          id: detail.id,
          title: pickConversationTitle(detail.title, cachedMeta?.title),
          createdAt: detail.createdAt,
          updatedAt: detail.updatedAt,
          messages: sanitizeRestoredMessages(mergeRestoredMessages(mapApiMessages(detail.messages), cached)),
          executionPreference: detail.executionPreference,
          kbId: detail.kbId ?? null,
          modelName: detail.modelName ?? null,
        })
        currentId.value = savedId
        persistCurrentId()
        return
      } catch (e) {
        if (isConversationNotFoundError(e)) {
          purgeStaleConversation(savedId)
        }
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
        await restoreCurrentFromSavedOrFirst()
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
          await restoreCurrentFromSavedOrFirst()
        }
        loaded = true
      } finally {
        initializing.value = false
      }
    })()

    return initPromise
  }

  /** 会话类型 → 消息分页条数：task 场景单轮工具调用量大，限制 5 条防溢出 */
  function pageSize(convId: string): number {
    const conv = conversations.value.find(c => c.id === convId)
    return conv?.kind === 'task' ? 5 : 10
  }

  async function loadDetail(id: string) {
    if (!isValidConversationId(id)) return
    try {
      const page = await getConversationMessages(id, { limit: pageSize(id) })
      const conv = conversations.value.find(c => c.id === id)
      if (conv) {
        const apiMsgs = mapApiMessages(page.messages)
        const cached = loadCachedMessages(id)
        conv.messages = sanitizeRestoredMessages(mergeRestoredMessages(apiMsgs, cached))
        if (conv.messages.length) {
          cacheMessages(id, conv.messages, {
            title: conv.title,
            createdAt: conv.createdAt,
            updatedAt: conv.updatedAt,
          })
        }
        historyHasMore.set(id, page.hasMore)
      }
    } catch (e) {
      if (isConversationNotFoundError(e)) {
        purgeStaleConversation(id)
        return
      }
      console.warn('[chatStore] 加载会话详情失败，尝试本地缓存', id, e)
      const conv = conversations.value.find(c => c.id === id)
      const cached = loadCachedMessages(id)
      if (conv && cached?.length) {
        conv.messages = sanitizeRestoredMessages(cached)
      }
    }
  }

  /**
   * 向上滚动加载更早历史：以当前已加载最旧 seq 为游标，前插去重并更新 hasMore。
   * 由 ChatView 触顶检测调用；加载成功后返回是否仍有更早消息。
   */
  async function loadHistory(id: string): Promise<boolean> {
    if (!isValidConversationId(id) || historyLoading.has(id)) return false
    const conv = conversations.value.find(c => c.id === id)
    const msgs = conv?.messages ?? []
    const minSeq = msgs.reduce(
      (min, m) => (typeof m.seq === 'number' ? Math.min(min, m.seq) : min),
      Number.POSITIVE_INFINITY,
    )
    if (!Number.isFinite(minSeq)) return false
    historyLoading.add(id)
    try {
      const page = await getConversationMessages(id, { beforeSeq: minSeq, limit: pageSize(id) })
      const byId = new Map(msgs.filter(m => m.id).map(m => [m.id!, m]))
      for (const m of page.messages) {
        if (m.id && !byId.has(m.id)) byId.set(m.id, mapApiMessages([m])[0])
      }
      // seq 缺失（后端未落库的流式最新消息）视为最新排尾部，避免触顶加载历史时最新消息被排到最前
      const merged = [...byId.values()].sort((a, b) => {
        const seqA = a.seq ?? Number.POSITIVE_INFINITY
        const seqB = b.seq ?? Number.POSITIVE_INFINITY
        return seqA - seqB
      })
      if (!conv) return page.hasMore
      conv.messages = sanitizeRestoredMessages(merged)
      if (conv.messages.length) {
        cacheMessages(id, conv.messages, {
          title: conv.title,
          createdAt: conv.createdAt,
          updatedAt: conv.updatedAt,
        })
      }
      historyHasMore.set(id, page.hasMore)
      return page.hasMore
    } catch (e) {
      console.warn('[chatStore] 加载更早历史失败', id, e)
      return false
    } finally {
      historyLoading.delete(id)
    }
  }

  function hasHistoryMore(id: string): boolean {
    return historyHasMore.get(id) ?? false
  }

  function isHistoryLoading(id: string): boolean {
    return historyLoading.has(id)
  }

  const current = computed(() =>
    conversations.value.find(c => c.id === currentId.value) ?? null
  )

  const sortedConversations = computed(() =>
    [...conversations.value].sort((a, b) => b.updatedAt - a.updatedAt)
  )

  async function create(params?: { kind?: string; workspaceId?: string; checkoutPath?: string }): Promise<string> {
    try {
      const created = await createConversation(params)
      const conv: Conversation = {
        id: created.id,
        title: created.title,
        createdAt: created.createdAt,
        updatedAt: created.updatedAt,
        messages: [],
        executionPreference: created.executionPreference,
        kbId: created.kbId ?? null,
        modelName: created.modelName ?? null,
        kind: created.kind,
        workspaceId: created.workspaceId ?? null,
        checkoutPath: created.checkoutPath ?? null,
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
      console.error('[chatStore] 后端创建会话失败', e)
      throw e
    }
  }

  /** stream 404 后移除幽灵会话并新建 */
  async function recoverAfterStaleConversation(): Promise<string> {
    if (isValidConversationId(currentId.value)) {
      purgeStaleConversation(currentId.value)
    }
    return create()
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
    conv.title = title.length > 15 ? title.slice(0, 15) + '…' : title || '新对话'
    upsertCachedIndex({
      id: conv.id,
      title: conv.title,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
    })
  }

  /** SSE meta:title 事件 — 后端 LLM 标题摘要生成完成（仅未改名才推送），直接覆盖 */
  function updateTitleFromStream(id: string, title: string) {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv || !title) return
    conv.title = title
    upsertCachedIndex({
      id: conv.id,
      title: conv.title,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
    })
  }

  async function rename(id: string, title: string): Promise<void> {
    const trimmed = title.trim()
    if (!trimmed) return
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    await updateConversationTitle(id, trimmed)
    conv.title = trimmed
    conv.updatedAt = Date.now()
    upsertCachedIndex({
      id: conv.id,
      title: conv.title,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
    })
  }

  function updateExecutionPreferenceLocal(id: string, pref: ExecutionPreference) {
    const conv = conversations.value.find(c => c.id === id)
    if (conv) conv.executionPreference = pref
  }

  function updateKbIdLocal(id: string, kb: string | null) {
    const conv = conversations.value.find(c => c.id === id)
    if (conv) conv.kbId = kb
  }

  function updateModelNameLocal(id: string, name: string | null) {
    const conv = conversations.value.find(c => c.id === id)
    if (conv) conv.modelName = name
  }

  /** 分支切换后重绑定会话 checkout 目录：后端持久化 + 本地会话同步；失败抛错由调用方中止流程 */
  async function updateCheckout(id: string, checkoutPath: string): Promise<void> {
    await updateConversationCheckout(id, checkoutPath)
    const conv = conversations.value.find(c => c.id === id)
    if (conv) conv.checkoutPath = checkoutPath
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
        const cachedMeta = loadCachedIndex().find(c => c.id === id)
        conversations.value.unshift({
          id: detail.id,
          title: pickConversationTitle(detail.title, cachedMeta?.title),
          createdAt: detail.createdAt,
          updatedAt: detail.updatedAt,
          messages: sanitizeRestoredMessages(mergeRestoredMessages(apiMsgs, cached)),
          executionPreference: detail.executionPreference,
          kbId: detail.kbId ?? null,
          modelName: detail.modelName ?? null,
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
    init, create, remove, rename, switchTo, ensureConversation, recoverAfterStaleConversation,
    updateTitle: updateTitleLocal,
    updateTitleFromStream,
    syncMessages, ensureCurrent, loadDetail, setConversationIdFromStream,
    updateExecutionPreferenceLocal,
    updateKbIdLocal,
    updateModelNameLocal,
    updateCheckout,
    loadHistory,
    hasHistoryMore,
    isHistoryLoading,
    pendingWorkspace,
    newTaskMode,
  }
})
