/**
 * 会话消息 localStorage 缓存 — 后端不可用或 reasoning 未落库时的恢复来源
 */
import type { ChatMessage } from './chat'
import type { ContentBlock } from './contentInterleave'
import { joinedContentBlocks, normalizeRestoredInterleavedContent } from './contentInterleave'
import { stepsHaveAwaitingHitl, getPendingHitlConfirmations } from './hitlSteps'
import type { ProcessingStep } from './processingSteps'

function countPlanNodeSteps(steps?: ProcessingStep[]): number {
  return steps?.filter(s => s.id.startsWith('node-')).length ?? 0
}

/** API 与缓存 steps 取更完整的一份（避免刷新后 node-* 步丢失） */
function pickRicherSteps(api?: ProcessingStep[], cached?: ProcessingStep[], forceCached?: boolean): ProcessingStep[] | undefined {
  if (forceCached) return cached
  if (!api?.length) return cached
  if (!cached?.length) return api
  const apiNodes = countPlanNodeSteps(api)
  const cachedNodes = countPlanNodeSteps(cached)
  if (cachedNodes > apiNodes) return cached
  if (apiNodes > cachedNodes) return api
  return (api.length >= cached.length) ? api : cached
}

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

function pickContentBlocks(
  api?: ContentBlock[],
  cached?: ContentBlock[],
): ContentBlock[] | undefined {
  if (!api?.length) return cached
  if (!cached?.length) return api
  const apiLen = joinedContentBlocks(api).length
  const cachedLen = joinedContentBlocks(cached).length
  return cachedLen > apiLen ? cached : api
}

/** 合并时优先保留更「新」的 assistant 终态（避免 API 陈旧 interrupted 覆盖本地 completed/streaming） */
function pickPreferredStatus(
  api?: ChatMessage['status'],
  cached?: ChatMessage['status'],
): ChatMessage['status'] | undefined {
  const rank = (s?: ChatMessage['status']) => {
    if (s === 'completed') return 4
    if (s === 'streaming') return 3
    if (s === 'interrupted') return 2
    if (s === 'failed') return 1
    return 0
  }
  if (rank(cached) >= rank(api)) return cached ?? api
  return api ?? cached
}

function pickLaterMs(a?: number, b?: number): number | undefined {
  if (a == null) return b
  if (b == null) return a
  return Math.max(a, b)
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
    const cachedHasHitl = stepsHaveAwaitingHitl(c.steps) || getPendingHitlConfirmations(c).length > 0
    const mergedPending = getPendingHitlConfirmations(a).length
      ? getPendingHitlConfirmations(a)
      : getPendingHitlConfirmations(c)
    const mergedMsg: ChatMessage = {
      ...a,
      content: pickLongerContent(a.content, c.content),
      reasoning: a.reasoning?.trim() ? a.reasoning : c.reasoning,
      steps: pickRicherSteps(a.steps, c.steps, cachedHasHitl),
      contentBlocks: pickContentBlocks(a.contentBlocks, c.contentBlocks),
      status: pickPreferredStatus(a.status, c.status),
      executionPlanId: a.executionPlanId ?? c.executionPlanId,
      executionPreference: a.executionPreference ?? c.executionPreference,
      pendingHitlConfirmations: mergedPending.length ? mergedPending : undefined,
      // 本地墙钟优先于 API hydrate，避免刷新后 20s→15s
      timelineStartedAt: c.timelineStartedAt ?? a.timelineStartedAt,
      timelineEndedAt: pickLaterMs(c.timelineEndedAt, a.timelineEndedAt),
    }
    if (mergedMsg.role === 'assistant') {
      normalizeRestoredInterleavedContent(mergedMsg)
    }
    merged.push(mergedMsg)
    if (a.id) byId.delete(a.id)
  }

  // 本地缓存可能比 API 多出「后端尚未落库的最新消息」：按 seq 增量追加尾部。
  // 不能按 cached.slice(api.length) 追加——分页场景下 API 只返回最近窗口，会与缓存窗口重复。
  const apiMaxSeq = api.reduce((max, m) => Math.max(max, m.seq ?? 0), 0)
  const mergedIds = new Set(merged.filter(m => m.id).map(m => m.id!))
  for (const c of cached) {
    if (!c.id || mergedIds.has(c.id)) continue
    if ((c.seq ?? 0) <= apiMaxSeq) continue
    merged.push(c)
  }

  return merged
}
