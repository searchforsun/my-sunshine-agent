/**
 * 会话消息 localStorage 缓存 — 后端不可用或 reasoning 未落库时的恢复来源。
 * 单会话缓存体积有上限（MAX_CACHE_BYTES），超限从最旧消息开始裁剪；
 * 站点配额耗尽时按最旧会话淘汰腾位，保证写入不因配额静默丢失。
 */
import type { ChatMessage } from './chat'
import type { ContentBlock } from './contentInterleave'
import { joinedContentBlocks, normalizeRestoredInterleavedContent } from './contentInterleave'
import { stepsHaveAwaitingHitl, getPendingHitlConfirmations } from './hitlSteps'
import { messageTimestamp } from './timelineMessageClock'
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
/** 单会话缓存体积上限：JSON 字符节数（UTF-8）。localStorage 站点配额约 5MB（按 UTF-16 单位计，
 * 中文按字节统计偏保守），单会话 4MB 为配额内可靠上限，余量留给索引等其余键 */
export const MAX_CACHE_BYTES = 4 * 1024 * 1024

/** 序列化体积（UTF-8 字节）：含 steps 的 task 消息单条可达百 KB 级，按字节而非条数控制 */
function serializedBytes(messages: ChatMessage[]): number {
  return new TextEncoder().encode(JSON.stringify(messages)).length
}

/** 超限裁剪最旧消息，至少保留最新 1 条；返回裁剪后的列表 */
function trimToLimit(messages: ChatMessage[]): ChatMessage[] {
  if (serializedBytes(messages) <= MAX_CACHE_BYTES) return messages
  let kept = messages
  while (kept.length > 1 && serializedBytes(kept) > MAX_CACHE_BYTES) {
    kept = kept.slice(1)
  }
  return kept
}

export interface CachedConversationMeta {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  /** 与库 SSOT 对齐，供刷新后侧栏横向 Tab 直接定位 */
  kind?: string
  workspaceId?: string | null
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
  const prev = loadCachedIndex().find(c => c.id === meta.id)
  const list = loadCachedIndex().filter(c => c.id !== meta.id)
  // 标题等局部更新勿冲掉已缓存的 kind / workspaceId
  list.unshift({
    ...meta,
    kind: meta.kind ?? prev?.kind,
    workspaceId: meta.workspaceId !== undefined ? meta.workspaceId : prev?.workspaceId,
  })
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
    localStorage.setItem(messagesKey(convId), JSON.stringify(trimToLimit(messages)))
    upsertCachedIndex({
      id: convId,
      title: meta?.title ?? '新对话',
      createdAt: meta?.createdAt ?? Date.now(),
      updatedAt: meta?.updatedAt ?? Date.now(),
      kind: meta?.kind,
      workspaceId: meta?.workspaceId,
    })
  } catch {
    // 配额耗尽：按 updated_at 最旧优先淘汰其他会话的消息缓存腾位后重试一次
    if (evictOldestConversations(convId)) {
      try {
        localStorage.setItem(messagesKey(convId), JSON.stringify(trimToLimit(messages)))
        upsertCachedIndex({
          id: convId,
          title: meta?.title ?? '新对话',
          createdAt: meta?.createdAt ?? Date.now(),
          updatedAt: meta?.updatedAt ?? Date.now(),
          kind: meta?.kind,
          workspaceId: meta?.workspaceId,
        })
        return
      } catch { /* 仍超配额则放弃本次缓存 */ }
    }
  }
}

/** 按 updated_at 最旧优先淘汰其他会话的消息缓存（不动索引键），返回是否淘汰过 */
function evictOldestConversations(keepConvId: string): boolean {
  const candidates = loadCachedIndex()
    .filter(c => c.id !== keepConvId)
    .sort((a, b) => a.updatedAt - b.updatedAt)
  if (candidates.length === 0) return false
  for (const meta of candidates) {
    try {
      localStorage.removeItem(messagesKey(meta.id))
    } catch { /* ignore */ }
  }
  return true
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
    const status = pickPreferredStatus(a.status, c.status)
    // 终态（completed/failed）是权威落库结果：缓存里带出的 pending 与 awaiting 工具步属过期残留，
    // 不再背回，避免「后端已不确认但前端仍显示写操作确认」。仅在消息未终态时保留缓存确认态供续处理。
    const isFinal = status === 'completed' || status === 'failed'
    const cachedHasHitl = stepsHaveAwaitingHitl(c.steps) || getPendingHitlConfirmations(c).length > 0
    const mergedPending = isFinal
      ? getPendingHitlConfirmations(a)
      : (getPendingHitlConfirmations(a).length
          ? getPendingHitlConfirmations(a)
          : getPendingHitlConfirmations(c))
    const mergedMsg: ChatMessage = {
      ...a,
      content: pickLongerContent(a.content, c.content),
      reasoning: a.reasoning?.trim() ? a.reasoning : c.reasoning,
      steps: pickRicherSteps(a.steps, c.steps, !isFinal && cachedHasHitl),
      contentBlocks: pickContentBlocks(a.contentBlocks, c.contentBlocks),
      status,
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

  // 本地缓存可能比 API 多出「后端尚未落库的最新消息」。
  // 不能按 cached.slice(api.length) 追加——分页场景下 API 只返回最近窗口，会与缓存窗口重复。
  // 分页去重：seq <= apiMaxSeq 的缓存消息属于更早历史窗口（由 loadHistory 负责），在此不再背回。
  const apiMaxSeq = api.reduce((max, m) => Math.max(max, m.seq ?? 0), 0)
  const mergedIds = new Set(merged.filter(m => m.id).map(m => m.id!))
  for (const c of cached) {
    if (!c.id || mergedIds.has(c.id)) continue
    // 流式消息在 SSE 中仅分配 id、不分配 seq（seq 在后端 commitFinal 落库后才补齐）。
    // 不能按 (seq ?? 0) <= apiMaxSeq 判断——会把「后端尚未落库」的最新缓存消息全部丢弃，
    // 导致刷新时 API 仅返回旧窗口、本地最新消息反而丢失，须等后端落库后才显示。
    if (typeof c.seq === 'number' && c.seq <= apiMaxSeq) continue
    merged.push(c)
  }

  // 跨轮次顺序以「创建时间」为唯一权威：合并自 API + 缓存的每条消息都已带 createdAt
  // （流式见 stampTimelineStarted 兜底、历史见 API Instant），按此升序归位，
  // 保证发生在窗口中间的中断轮次不会被后续已完成消息挤到后面。
  return merged.slice().sort((a, b) => messageTimestamp(a) - messageTimestamp(b))
}


