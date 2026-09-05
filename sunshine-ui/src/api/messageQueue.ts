/**
 * 输入框任务队列 —— 会话级待发消息队列（仅前端）。
 * 流式回答期间发送的消息入队等待，当前轮结束后自动出队按快照原样发出；
 * 队列按会话隔离，localStorage 持久化（刷新后保留），会话删除时同步清理。
 */
import { computed, ref } from 'vue'
import type { SendOptions } from './chatSessionRegistry'

export interface QueuedMessage {
  id: string
  text: string
  /** 入队时刻的发送快照（模型/偏好/技能等），出队时原样使用 */
  sendOptions: SendOptions
  createdAt: number
}

const STORAGE_KEY_PREFIX = 'sunshine-message-queue:'
/** 单会话队列软上限：超出拒绝入队，防止无限堆积 */
const MAX_QUEUE_SIZE = 50

const storageKey = (convId: string) => STORAGE_KEY_PREFIX + convId

/** 当前激活会话 id（由 ChatView 绑定），驱动 activeQueue 响应式切换 */
const activeConversationId = ref<string>('')

/** 全量队列（按会话分桶，内存 SSOT），activeQueue 为其视图 */
const queues = ref<Record<string, QueuedMessage[]>>({})

function safeParse(raw: string | null): QueuedMessage[] | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? (parsed as QueuedMessage[]) : null
  } catch {
    return null
  }
}

function loadQueue(convId: string): QueuedMessage[] {
  return safeParse(localStorage.getItem(storageKey(convId))) ?? []
}

function persistQueue(convId: string): void {
  const list = queues.value[convId] ?? []
  try {
    localStorage.setItem(storageKey(convId), JSON.stringify(list))
  } catch {
    // 配额满等写入失败不阻塞内存队列：本次刷新周期内队列仍可用
  }
}

/** 绑定当前会话并惰性加载其持久化队列（同会话仅读一次 localStorage） */
function setActiveConversation(convId: string): void {
  activeConversationId.value = convId
  if (convId && queues.value[convId] === undefined) {
    queues.value[convId] = loadQueue(convId)
  }
}

const activeQueue = computed<QueuedMessage[]>(() => {
  const id = activeConversationId.value
  return (id && queues.value[id]) || []
})

function enqueue(convId: string, text: string, sendOptions: SendOptions): boolean {
  if (!convId || !text.trim()) return false
  const list = queues.value[convId] ?? []
  if (list.length >= MAX_QUEUE_SIZE) return false
  queues.value[convId] = [
    ...list,
    { id: `q-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, text, sendOptions, createdAt: Date.now() },
  ]
  persistQueue(convId)
  return true
}

function dequeue(convId: string): QueuedMessage | null {
  const list = queues.value[convId] ?? []
  if (!list.length) return null
  const [head, ...rest] = list
  queues.value[convId] = rest
  persistQueue(convId)
  return head
}

/** 查看队首（不移除）；会话未加载时惰性读 localStorage */
function peekQueue(convId: string): QueuedMessage | null {
  if (!convId) return null
  const list = queues.value[convId] ?? loadQueue(convId)
  return list[0] ?? null
}

/** 队列长度；会话未加载时惰性读 localStorage */
function sizeOf(convId: string): number {
  if (!convId) return 0
  return (queues.value[convId] ?? loadQueue(convId)).length
}

function removeItem(convId: string, id: string): void {
  const list = queues.value[convId] ?? []
  queues.value[convId] = list.filter(item => item.id !== id)
  persistQueue(convId)
}

function updateItemText(convId: string, id: string, text: string, imageUrls?: string[]): void {
  const list = queues.value[convId] ?? []
  queues.value[convId] = list.map(item => (item.id === id
    ? { ...item, text, sendOptions: { ...item.sendOptions, ...(imageUrls ? { imageUrls: [...imageUrls] } : {}) } }
    : item))
  persistQueue(convId)
}

/** 拖拽排序：被拖项移动到落点项位置（其余项顺次腾位） */
function moveItemTo(convId: string, fromId: string, toId: string): void {
  const list = queues.value[convId] ?? []
  const from = list.findIndex(item => item.id === fromId)
  const to = list.findIndex(item => item.id === toId)
  if (from === -1 || to === -1 || from === to) return
  const next = [...list]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  queues.value[convId] = next
  persistQueue(convId)
}

/** 出队后发送失败时回插队首，保证消息不丢 */
function restoreFront(convId: string, item: QueuedMessage): void {
  if (!convId || !item) return
  queues.value[convId] = [item, ...(queues.value[convId] ?? [])]
  persistQueue(convId)
}

/** 会话删除时同步清理持久化队列，避免 localStorage 残留孤儿 key */
function dropQueue(convId: string): void {
  delete queues.value[convId]
  try {
    localStorage.removeItem(storageKey(convId))
  } catch { /* 忽略 */ }
}

export function useMessageQueue() {
  return {
    activeConversationId,
    activeQueue,
    setActiveConversation,
    enqueue,
    dequeue,
    peekQueue,
    sizeOf,
    removeItem,
    updateItemText: updateItemText,
    moveItemTo,
    restoreFront,
    dropQueue,
  }
}
