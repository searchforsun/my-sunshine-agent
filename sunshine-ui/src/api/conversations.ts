import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import type { ExecutionPreference } from './executionModes'
import { isExecutionPreference } from './executionModes'
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'
import { normalizeStep, parseContentBlocks } from './processingSteps'
import type { ContentBlock } from './contentInterleave'
import { hydratePlanAnswerFromContent, normalizeRestoredInterleavedContent, sanitizePlanAssistantMessage } from './contentInterleave'
import { ApiError, parseBffPayload } from './apiError'

const API_BASE = () => resolveApiBase()
export interface ConversationSummary {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  executionPreference?: ExecutionPreference
  kbId?: string | null
  /** 会话绑定模型（注册表 model_name） */
  modelName?: string | null
  /** chat / task */
  kind?: string
  workspaceId?: string | null
  /** task 会话的 checkout 目录（/workspace/branches/{checkoutId}） */
  checkoutPath?: string | null
}

/** 聚合搜索返回项：在会话摘要基础上附带命中消息正文摘要 */
export interface ConversationSearchItem {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  /** chat / task */
  kind?: string
  workspaceId?: string | null
  snippet?: string
}

export interface ConversationMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  steps?: ProcessingStep[]
  contentBlocks?: ContentBlock[]
  status?: string
  intent?: string
  seq?: number
  createdAt?: string
  updatedAt?: string
  executionPlanId?: string
  executionPreference?: ExecutionPreference
}

export interface ConversationDetail extends ConversationSummary {
  messages: ConversationMessage[]
}

function toTimestamp(iso: string | undefined): number {
  if (!iso) return Date.now()
  const t = Date.parse(iso)
  return Number.isNaN(t) ? Date.now() : t
}

export function isValidConversationId(id: unknown): id is string {
  return typeof id === 'string' && id.length > 0 && id !== 'undefined' && id !== 'null'
}

function requireConversationId(raw: Record<string, unknown>): string {
  if (!isValidConversationId(raw.id)) {
    throw new ApiError('数据加载失败，请刷新重试', { kind: 'unknown' })
  }
  return raw.id
}

function mapSummary(raw: Record<string, unknown>): ConversationSummary {
  const pref = raw.executionPreference
  return {
    id: requireConversationId(raw),
    title: String(raw.title ?? '新对话'),
    createdAt: toTimestamp(raw.createdAt as string | undefined),
    updatedAt: toTimestamp(raw.updatedAt as string | undefined),
    executionPreference: isExecutionPreference(pref) ? pref : undefined,
    kbId: typeof raw.kbId === 'string' ? raw.kbId : null,
    modelName: typeof raw.modelName === 'string' ? raw.modelName : null,
    kind: typeof raw.kind === 'string' ? raw.kind : undefined,
    workspaceId: typeof raw.workspaceId === 'string' ? raw.workspaceId : null,
    checkoutPath: typeof raw.checkoutPath === 'string' ? raw.checkoutPath : null,
  }
}

function parseSteps(raw: unknown): ProcessingStep[] | undefined {
  if (!raw) return undefined
  if (typeof raw === 'string') {
    try {
      const arr = JSON.parse(raw) as unknown[]
      return arr
        .map(item => normalizeStep(item as Record<string, unknown>))
        .filter(Boolean) as ProcessingStep[]
    } catch {
      return undefined
    }
  }
  if (Array.isArray(raw)) {
    const steps = raw
      .map(item => normalizeStep(item as Record<string, unknown>))
      .filter(Boolean) as ProcessingStep[]
    return steps.length ? steps : undefined
  }
  return undefined
}

function parseMessageContentBlocks(raw: unknown): ContentBlock[] | undefined {
  if (raw == null) return undefined
  if (typeof raw === 'string') {
    if (!raw.trim()) return undefined
    try {
      return parseContentBlocks(JSON.parse(raw))
    } catch {
      return undefined
    }
  }
  return parseContentBlocks(raw)
}

function parseMessage(m: Record<string, unknown>): ConversationMessage {
  const msg: ConversationMessage = {
    id: String(m.id),
    role: m.role as 'user' | 'assistant',
    content: String(m.content ?? ''),
    reasoning: typeof m.reasoning === 'string' ? m.reasoning : undefined,
    steps: parseSteps(m.steps),
    contentBlocks: parseMessageContentBlocks(m.contentBlocks),
    status: m.status as string | undefined,
    intent: m.intent as string | undefined,
    seq: m.seq as number | undefined,
    createdAt: m.createdAt as string | undefined,
    updatedAt: m.updatedAt as string | undefined,
    executionPlanId: typeof m.executionPlanId === 'string' ? m.executionPlanId : undefined,
    executionPreference: isExecutionPreference(m.executionPreference) ? m.executionPreference : undefined,
  }
  if (msg.role === 'assistant') {
    sanitizePlanAssistantMessage(msg)
    hydratePlanAnswerFromContent(msg)
    normalizeRestoredInterleavedContent(msg as ChatMessage)
  }
  return msg
}

function mapDetail(raw: Record<string, unknown>): ConversationDetail {
  const messages = (raw.messages as Record<string, unknown>[] | undefined ?? []).map(parseMessage)
  return { ...mapSummary(raw), messages }
}

/** 游标分页结果：messages 升序，hasMore 指示更早历史仍存在 */
export interface MessagePage {
  messages: ConversationMessage[]
  hasMore: boolean
}

function unwrapList(raw: unknown): Record<string, unknown>[] {
  if (!Array.isArray(raw)) {
    throw new ApiError('数据加载失败，请刷新重试', { kind: 'unknown' })
  }
  return raw as Record<string, unknown>[]
}

function unwrapObject(raw: unknown): Record<string, unknown> {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
    throw new ApiError('数据加载失败，请刷新重试', { kind: 'unknown' })
  }
  return raw as Record<string, unknown>
}

export async function listConversations(): Promise<ConversationSummary[]> {
  const res = await fetch(`${API_BASE()}/api/conversations`, { headers: apiHeaders() })
  return unwrapList(await parseBffPayload(res)).map(mapSummary)
}

function mapSearchItem(raw: Record<string, unknown>): ConversationSearchItem {
  return {
    id: requireConversationId(raw),
    title: String(raw.title ?? '新对话'),
    createdAt: toTimestamp(raw.createdAt as string | undefined),
    updatedAt: toTimestamp(raw.updatedAt as string | undefined),
    kind: typeof raw.kind === 'string' ? raw.kind : undefined,
    workspaceId: typeof raw.workspaceId === 'string' ? raw.workspaceId : null,
    snippet: typeof raw.snippet === 'string' ? raw.snippet : undefined,
  }
}

/** 聚合搜索对话与任务会话（标题 + 消息正文），keyword 为空时后端返回空列表 */
export async function searchConversations(keyword: string): Promise<ConversationSearchItem[]> {
  const params = new URLSearchParams({ q: keyword })
  const res = await fetch(`${API_BASE()}/api/conversations/search?${params}`, { headers: apiHeaders() })
  return unwrapList(await parseBffPayload(res)).map(mapSearchItem)
}

export async function createConversation(params?: {
  kind?: string
  workspaceId?: string
  checkoutPath?: string
}): Promise<ConversationSummary> {
  const body: Record<string, unknown> = {}
  if (params?.kind) body.kind = params.kind
  if (params?.workspaceId) body.workspaceId = params.workspaceId
  if (params?.checkoutPath) body.checkoutPath = params.checkoutPath
  const res = await fetch(`${API_BASE()}/api/conversations`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify(body),
  })
  return mapSummary(unwrapObject(await parseBffPayload(res)))
}

export async function getConversation(id: string): Promise<ConversationDetail> {
  if (!isValidConversationId(id)) {
    throw new ApiError('数据加载失败，请刷新重试', { kind: 'unknown' })
  }
  const res = await fetch(`${API_BASE()}/api/conversations/${id}`, { headers: apiHeaders() })
  return mapDetail(unwrapObject(await parseBffPayload(res)))
}

/**
 * 会话消息游标分页：beforeSeq<=0 返回最近 limit 条；否则返回 seq<beforeSeq 的最近 limit 条。
 * IM 标准：首屏加载最近消息，向上滚动按游标加载更早历史。
 */
export async function getConversationMessages(
  id: string,
  opts: { beforeSeq?: number; limit?: number },
): Promise<MessagePage> {
  if (!isValidConversationId(id)) {
    throw new ApiError('数据加载失败，请刷新重试', { kind: 'unknown' })
  }
  const params = new URLSearchParams({
    beforeSeq: String(opts.beforeSeq ?? 0),
    limit: String(opts.limit ?? 30),
  })
  const res = await fetch(`${API_BASE()}/api/conversations/${id}/messages?${params}`, {
    headers: apiHeaders(),
  })
  const raw = unwrapObject(await parseBffPayload(res))
  return {
    messages: (raw.messages as Record<string, unknown>[] | undefined ?? []).map(parseMessage),
    hasMore: raw.hasMore === true,
  }
}

/** 分支切换后更新 task 会话绑定的 checkout 目录（本地 + 后端持久化） */
export async function updateConversationCheckout(id: string, checkoutPath: string): Promise<void> {
  const res = await fetch(`${API_BASE()}/api/conversations/${id}/checkout`, {
    method: 'PATCH',
    headers: apiHeaders(),
    body: JSON.stringify({ checkoutPath }),
  })
  await parseBffPayload(res, { allowEmptyData: true })
}

export async function deleteConversation(id: string): Promise<void> {
  const res = await fetch(`${API_BASE()}/api/conversations/${id}`, {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseBffPayload(res, { allowEmptyData: true })
}

export async function updateConversationTitle(id: string, title: string): Promise<void> {
  const res = await fetch(`${API_BASE()}/api/conversations/${id}`, {
    method: 'PATCH',
    headers: apiHeaders(),
    body: JSON.stringify({ title }),
  })
  await parseBffPayload(res, { allowEmptyData: true })
}
