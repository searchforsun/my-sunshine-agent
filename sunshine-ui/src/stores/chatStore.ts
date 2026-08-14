/**
 * 对话历史 Pinia Store — 后端 API 为主存储，localStorage 缓存兜底（含 reasoning）
 */
import { defineStore, acceptHMRUpdate } from 'pinia'
import { ref, computed, reactive } from 'vue'
import { useAuthStore } from './authStore'
import { normalizeSidebarSectionsLayout } from '../api/sidebarSectionsLayouts'
import type { ChatMessage } from '../api/chat'
import type { ConversationMessage } from '../api/conversations'
import type { ExecutionPreference } from '../api/executionModes'
import {
  listConversationsPage,
  createConversation,
  getConversation,
  getConversationMessages,
  deleteConversation,
  updateConversationTitle,
  updateConversationCheckout,
  isValidConversationId,
  type ConversationSummary,
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
import { purgeConversationsForWorkspace } from '../api/conversationWorkspacePurge'
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
/** 纵向点「更多」：10；横向滚底加载：30 */
const CHAT_SIDEBAR_PAGE_SIZE_VERTICAL = 10
const CHAT_SIDEBAR_PAGE_SIZE_HORIZONTAL = 30
const TASK_SIDEBAR_PAGE_SIZE = 10

function chatSidebarPageSize(): number {
  return normalizeSidebarSectionsLayout(useAuthStore().user?.sidebarSectionsLayout) === 'horizontal'
    ? CHAT_SIDEBAR_PAGE_SIZE_HORIZONTAL
    : CHAT_SIDEBAR_PAGE_SIZE_VERTICAL
}

/** 每会话历史加载状态：hasMore 表示更早消息仍存在；loading 防并发 */
const historyHasMore = new Map<string, boolean>()
const historyLoading = new Set<string>()

/** API 仍为默认标题时保留本地已推导标题，避免流式过程中 loadDetail / 侧栏点击回退 */
function pickConversationTitle(apiTitle: string, localTitle?: string): string {
  if (apiTitle && apiTitle !== DEFAULT_CONV_TITLE) return apiTitle
  if (localTitle && localTitle !== DEFAULT_CONV_TITLE) return localTitle
  return apiTitle || localTitle || DEFAULT_CONV_TITLE
}

/** 任务会话：仅 kind=task。工作区能力挂在 workspaceId 上（chat 会话绑定工作区后仍是 chat，不归任务形态） */
export function isTaskConversation(c: {
  kind?: string | null
}): boolean {
  return c.kind === 'task'
}

function summaryToConversation(
  c: ConversationSummary,
  prev?: Conversation,
  cachedTitle?: string,
): Conversation {
  const title = pickConversationTitle(c.title, prev?.title ?? cachedTitle)
  upsertCachedIndex({
    id: c.id,
    title,
    createdAt: c.createdAt,
    updatedAt: c.updatedAt,
    kind: c.kind ?? prev?.kind,
    workspaceId: c.workspaceId ?? prev?.workspaceId ?? null,
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
  /** 对话侧栏分页 */
  const chatSidebarHasMore = ref(false)
  const chatSidebarLoadingMore = ref(false)
  let chatSidebarOffset = 0
  /** 工作区 → 任务会话分页 */
  const workspaceTaskHasMore = reactive<Record<string, boolean>>({})
  const workspaceTaskLoadingMore = reactive<Record<string, boolean>>({})
  const workspaceTaskOffset = reactive<Record<string, number>>({})
  const workspaceTaskLoaded = reactive<Record<string, boolean>>({})
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

  /** 首屏：用本页 chat 列表替换非 task；保留已加载的 task */
  function replaceChatPage(list: ConversationSummary[]): void {
    const prevById = new Map(conversations.value.map(c => [c.id, c]))
    const cachedIndex = loadCachedIndex()
    const tasks = conversations.value.filter(c => isTaskConversation(c))
    const chats = list
      .filter(c => isValidConversationId(c.id) && !isTaskConversation(c))
      .map(c => {
        const prev = prevById.get(c.id)
        const cachedMeta = cachedIndex.find(m => m.id === c.id)
        return summaryToConversation(c, prev, cachedMeta?.title)
      })
    conversations.value = [...chats, ...tasks]
  }

  /** 追加会话（去重合并字段） */
  function appendSummaries(list: ConversationSummary[]): void {
    const prevById = new Map(conversations.value.map(c => [c.id, c]))
    const cachedIndex = loadCachedIndex()
    for (const c of list) {
      if (!isValidConversationId(c.id)) continue
      const prev = prevById.get(c.id)
      const cachedMeta = cachedIndex.find(m => m.id === c.id)
      const next = summaryToConversation(c, prev, cachedMeta?.title)
      if (prev) {
        Object.assign(prev, next, { messages: prev.messages })
      } else {
        conversations.value.push(next)
        prevById.set(c.id, next)
      }
    }
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
          kind: detail.kind,
          workspaceId: detail.workspaceId ?? null,
          checkoutPath: detail.checkoutPath ?? null,
        })
        upsertCachedIndex({
          id: detail.id,
          title: pickConversationTitle(detail.title, cachedMeta?.title),
          createdAt: detail.createdAt,
          updatedAt: detail.updatedAt,
          kind: detail.kind,
          workspaceId: detail.workspaceId ?? null,
        })
        currentId.value = savedId
        persistCurrentId()
        if (detail.workspaceId) {
          void ensureWorkspaceTasks(detail.workspaceId)
        }
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
        const page = await listConversationsPage({
          kind: 'chat',
          limit: chatSidebarPageSize(),
          offset: 0,
        })
        replaceChatPage(page.items)
        chatSidebarOffset = page.items.length
        chatSidebarHasMore.value = page.hasMore
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

  async function loadMoreChats(): Promise<void> {
    if (!chatSidebarHasMore.value || chatSidebarLoadingMore.value) return
    chatSidebarLoadingMore.value = true
    try {
      const page = await listConversationsPage({
        kind: 'chat',
        limit: chatSidebarPageSize(),
        offset: chatSidebarOffset,
      })
      appendSummaries(page.items)
      chatSidebarOffset += page.items.length
      chatSidebarHasMore.value = page.hasMore
    } catch (e) {
      console.warn('[chatStore] 对话侧栏加载更多失败', e)
    } finally {
      chatSidebarLoadingMore.value = false
    }
  }

  /** 纵向折叠对话：侧栏收回首屏 10 条，再展开仍可点「更多」 */
  function collapseChats(): void {
    const pageSize = CHAT_SIDEBAR_PAGE_SIZE_VERTICAL
    const chats = conversations.value
      .filter(c => !isTaskConversation(c))
      .sort((a, b) => b.updatedAt - a.updatedAt)
    if (chats.length <= pageSize) {
      chatSidebarOffset = chats.length
      return
    }
    const keepIds = new Set(chats.slice(0, pageSize).map(c => c.id))
    if (currentId.value && chats.some(c => c.id === currentId.value)) {
      keepIds.add(currentId.value)
    }
    conversations.value = conversations.value.filter(
      c => isTaskConversation(c) || keepIds.has(c.id),
    )
    chatSidebarOffset = pageSize
    chatSidebarHasMore.value = true
  }

  /** 展开工作区时拉取首屏任务会话（每页 10） */
  async function ensureWorkspaceTasks(workspaceId: string): Promise<void> {
    if (!workspaceId || workspaceTaskLoaded[workspaceId]) return
    workspaceTaskLoadingMore[workspaceId] = true
    try {
      const page = await listConversationsPage({
        kind: 'task',
        workspaceId,
        limit: TASK_SIDEBAR_PAGE_SIZE,
        offset: 0,
      })
      appendSummaries(page.items)
      workspaceTaskOffset[workspaceId] = page.items.length
      workspaceTaskHasMore[workspaceId] = page.hasMore
      workspaceTaskLoaded[workspaceId] = true
    } catch (e) {
      console.warn('[chatStore] 工作区任务首屏失败', e)
    } finally {
      workspaceTaskLoadingMore[workspaceId] = false
    }
  }

  async function loadMoreWorkspaceTasks(workspaceId: string): Promise<void> {
    if (!workspaceId || !workspaceTaskHasMore[workspaceId] || workspaceTaskLoadingMore[workspaceId]) return
    workspaceTaskLoadingMore[workspaceId] = true
    try {
      const offset = workspaceTaskOffset[workspaceId] ?? 0
      const page = await listConversationsPage({
        kind: 'task',
        workspaceId,
        limit: TASK_SIDEBAR_PAGE_SIZE,
        offset,
      })
      appendSummaries(page.items)
      workspaceTaskOffset[workspaceId] = offset + page.items.length
      workspaceTaskHasMore[workspaceId] = page.hasMore
    } catch (e) {
      console.warn('[chatStore] 工作区任务加载更多失败', e)
    } finally {
      workspaceTaskLoadingMore[workspaceId] = false
    }
  }

  /** 折叠工作区：侧栏任务列表收回首屏 10 条（与分页 limit 一致），再展开仍可点「更多」 */
  function collapseWorkspaceTasks(workspaceId: string): void {
    if (!workspaceId) return
    const tasks = conversations.value
      .filter(c => isTaskConversation(c) && c.workspaceId === workspaceId)
      .sort((a, b) => b.updatedAt - a.updatedAt)
    if (tasks.length <= TASK_SIDEBAR_PAGE_SIZE) {
      workspaceTaskOffset[workspaceId] = tasks.length
      return
    }
    const keepIds = new Set(tasks.slice(0, TASK_SIDEBAR_PAGE_SIZE).map(c => c.id))
    // 当前打开的任务即使不在首屏也保留，避免折叠后主区会话从列表消失
    if (currentId.value && tasks.some(t => t.id === currentId.value)) {
      keepIds.add(currentId.value)
    }
    conversations.value = conversations.value.filter(
      c => !(isTaskConversation(c) && c.workspaceId === workspaceId) || keepIds.has(c.id),
    )
    workspaceTaskOffset[workspaceId] = TASK_SIDEBAR_PAGE_SIZE
    workspaceTaskHasMore[workspaceId] = true
    workspaceTaskLoaded[workspaceId] = true
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
            kind: conv.kind,
            workspaceId: conv.workspaceId ?? null,
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
          kind: conv.kind,
          workspaceId: conv.workspaceId ?? null,
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

  /** 可复用的空白对话：已确认无消息（含本地缓存）；不看标题。
   * historyHasMore 有记录表示已拉过消息页或本端刚创建，避免列表项未 hydrate 时误判为空。 */
  function findBlankChat(): Conversation | undefined {
    const blanks = conversations.value.filter(c => {
      if (isTaskConversation(c)) return false
      if (!historyHasMore.has(c.id)) return false
      if (c.messages?.length) return false
      const cached = loadCachedMessages(c.id)
      return !cached?.length
    })
    return blanks.sort((a, b) => b.updatedAt - a.updatedAt)[0]
  }

  async function create(params?: { kind?: string; workspaceId?: string; checkoutPath?: string }): Promise<string> {
    const kind = params?.kind ?? 'chat'
    // 点「新对话」：已有空白会话则直接定位，避免侧栏堆多个空会话。
    // chat 会话工作区由后端在首次执行脚本时懒绑定，空白复用不判断工作区
    if (kind === 'chat' && !params?.workspaceId) {
      const blank = findBlankChat()
      if (blank) {
        if (currentId.value !== blank.id) {
          await switchTo(blank.id)
        }
        return blank.id
      }
    }
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
        kind: created.kind ?? params?.kind,
        workspaceId: created.workspaceId ?? params?.workspaceId ?? null,
        checkoutPath: created.checkoutPath ?? params?.checkoutPath ?? null,
      }
      conversations.value.unshift(conv)
      currentId.value = conv.id
      persistCurrentId()
      historyHasMore.set(conv.id, false)
      upsertCachedIndex({
        id: conv.id,
        title: conv.title,
        createdAt: conv.createdAt,
        updatedAt: conv.updatedAt,
        kind: conv.kind,
        workspaceId: conv.workspaceId ?? null,
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

  /**
   * 工作区已在后端删除（含关联会话）后，同步清理前端列表/localStorage，
   * 并在当前会话属该工作区（或新任务挂在其上）时切到最新剩余会话。
   */
  async function removeByWorkspace(workspaceId: string): Promise<void> {
    if (!workspaceId) return
    delete workspaceTaskHasMore[workspaceId]
    delete workspaceTaskLoadingMore[workspaceId]
    delete workspaceTaskOffset[workspaceId]
    delete workspaceTaskLoaded[workspaceId]
    const result = purgeConversationsForWorkspace(
      conversations.value,
      workspaceId,
      currentId.value,
      pendingWorkspace.value?.wsId ?? null,
    )
    if (result.clearPending) {
      pendingWorkspace.value = null
      newTaskMode.value = false
    }
    for (const id of result.removedIds) {
      removeCachedIndex(id)
    }
    if (result.removedIds.length) {
      conversations.value = result.remaining
    }
    if (!result.didSwitch) return
    currentId.value = result.nextCurrentId
    persistCurrentId()
    if (currentId.value) await loadDetail(currentId.value)
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
    // 数据层保留完整标题，超宽显示交给 CSS ellipsis，避免硬截断丢失内容
    conv.title = title || '新对话'
    upsertCachedIndex({
      id: conv.id,
      title: conv.title,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
      kind: conv.kind,
      workspaceId: conv.workspaceId ?? null,
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
      kind: conv.kind,
      workspaceId: conv.workspaceId ?? null,
    })
  }

  /** 发送消息后本地同步会话最新活动时间：后端在消息落库时更新 updatedAt（SSE 未下行该值），侧栏按此排序/展示时间 */
  function touchConversation(id: string): void {
    const conv = conversations.value.find(c => c.id === id)
    if (!conv) return
    conv.updatedAt = Date.now()
    upsertCachedIndex({
      id: conv.id,
      title: conv.title,
      createdAt: conv.createdAt,
      updatedAt: conv.updatedAt,
      kind: conv.kind,
      workspaceId: conv.workspaceId ?? null,
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
      kind: conv.kind,
      workspaceId: conv.workspaceId ?? null,
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
        kind: conv.kind,
        workspaceId: conv.workspaceId ?? null,
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
          kind: detail.kind,
          workspaceId: detail.workspaceId ?? null,
          checkoutPath: detail.checkoutPath ?? null,
        })
        upsertCachedIndex({
          id: detail.id,
          title: pickConversationTitle(detail.title, cachedMeta?.title),
          createdAt: detail.createdAt,
          updatedAt: detail.updatedAt,
          kind: detail.kind,
          workspaceId: detail.workspaceId ?? null,
        })
        if (detail.workspaceId) {
          void ensureWorkspaceTasks(detail.workspaceId)
        }
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
        kind: oldConv?.kind,
        workspaceId: oldConv?.workspaceId ?? null,
        checkoutPath: oldConv?.checkoutPath ?? null,
        executionPreference: oldConv?.executionPreference,
        kbId: oldConv?.kbId ?? null,
        modelName: oldConv?.modelName ?? null,
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
    const next = conversations.value.find(c => c.id === newId)
    upsertCachedIndex({
      id: newId,
      title: next?.title ?? '新对话',
      createdAt: oldConv?.createdAt ?? Date.now(),
      updatedAt: Date.now(),
      kind: next?.kind ?? oldConv?.kind,
      workspaceId: next?.workspaceId ?? oldConv?.workspaceId ?? null,
    })
  }

  return {
    conversations, currentId, current, sortedConversations, initializing,
    chatSidebarHasMore, chatSidebarLoadingMore,
    workspaceTaskHasMore, workspaceTaskLoadingMore, workspaceTaskLoaded,
    init, create, remove, removeByWorkspace, rename, switchTo, ensureConversation, recoverAfterStaleConversation,
    loadMoreChats, collapseChats, ensureWorkspaceTasks, loadMoreWorkspaceTasks, collapseWorkspaceTasks,
    updateTitle: updateTitleLocal,
    updateTitleFromStream,
    touchConversation,
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

if (import.meta.hot) {
  import.meta.hot.accept(acceptHMRUpdate(useChatStore, import.meta.hot))
}