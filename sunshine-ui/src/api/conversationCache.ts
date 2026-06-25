/**
 * 会话消息 localStorage 缓存 — 后端不可用或 reasoning 未落库时的恢复来源
 */
import type { ChatMessage } from './chat'

const INDEX_KEY = 'sunshine-conv-index'
const messagesKey = (id: string) => `sunshine-conv-msgs:${id}`

export interface CachedConversationMeta {
  id: string
  title: string
  createdAt: number
  updatedAt: number
}

function safeParse<T>(raw: string | null): T | null {
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

export function loadCachedIndex(): CachedConversationMeta[] {
  return safeParse<CachedConversationMeta[]>(localStorage.getItem(INDEX_KEY)) ?? []
}

function saveCachedIndex(list: CachedConversationMeta[]): void {
  try {
    localStorage.setItem(INDEX_KEY, JSON.stringify(list))
  } catch { /* quota */ }
}

export function upsertCachedIndex(meta: CachedConversationMeta): void {
  const list = loadCachedIndex().filter(c => c.id !== meta.id)
  list.unshift(meta)
  saveCachedIndex(list.slice(0, 80))
}

export function removeCachedIndex(id: string): void {
  saveCachedIndex(loadCachedIndex().filter(c => c.id !== id))
  try {
    localStorage.removeItem(messagesKey(id))
  } catch { /* ignore */ }
}

export function cacheMessages(convId: string, messages: ChatMessage[], meta?: Partial<CachedConversationMeta>): void {
  if (!convId || messages.length === 0) return
  try {
    localStorage.setItem(messagesKey(convId), JSON.stringify(messages))
    upsertCachedIndex({
      id: convId,
      title: meta?.title ?? '新对话',
      createdAt: meta?.createdAt ?? Date.now(),
      updatedAt: meta?.updatedAt ?? Date.now(),
    })
  } catch { /* quota */ }
}

export function loadCachedMessages(convId: string): ChatMessage[] | null {
  return safeParse<ChatMessage[]>(localStorage.getItem(messagesKey(convId)))
}

function pickLongerContent(a: string, b: string): string {
  if (!b.trim()) return a
  if (!a.trim()) return b
  return b.length >= a.length ? b : a
}

/** API 与本地缓存合并：取更长正文，保留 reasoning */
export function mergeRestoredMessages(api: ChatMessage[], cached: ChatMessage[] | null): ChatMessage[] {
  if (!cached?.length) return api
  if (!api.length) return cached

  const byId = new Map(cached.filter(m => m.id).map(m => [m.id!, m]))
  const merged: ChatMessage[] = []

  for (let i = 0; i < api.length; i++) {
    const a = api[i]
    const c = a.id ? byId.get(a.id) : cached[i]
    if (!c) {
      merged.push(a)
      continue
    }
    merged.push({
      ...a,
      content: pickLongerContent(a.content, c.content),
      reasoning: a.reasoning?.trim() ? a.reasoning : c.reasoning,
      steps: a.steps?.length ? a.steps : c.steps,
      status: a.status ?? c.status,
      executionPreference: a.executionPreference ?? c.executionPreference,
    })
    if (a.id) byId.delete(a.id)
  }

  if (api.length < cached.length) {
    merged.push(...cached.slice(api.length))
  }

  return merged
}
