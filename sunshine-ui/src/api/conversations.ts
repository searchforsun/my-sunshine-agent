import { apiHeaders } from '../stores/authStore'
import { BFF_API_BASE } from './config'
import type { ProcessingStep } from './processingSteps'
import { migrateV1Step, normalizeStep } from './processingSteps'

const API_BASE = BFF_API_BASE
export interface ConversationSummary {
  id: string
  title: string
  createdAt: number
  updatedAt: number
}

export interface ConversationMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  reasoning?: string
  steps?: ProcessingStep[]
  status?: string
  intent?: string
  seq?: number
  createdAt?: string
  executionPlanId?: string
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
    throw new Error('会话 id 无效')
  }
  return raw.id
}

function mapSummary(raw: Record<string, unknown>): ConversationSummary {
  return {
    id: requireConversationId(raw),
    title: String(raw.title ?? '新对话'),
    createdAt: toTimestamp(raw.createdAt as string | undefined),
    updatedAt: toTimestamp(raw.updatedAt as string | undefined),
  }
}

function parseSteps(raw: unknown): ProcessingStep[] | undefined {
  if (!raw) return undefined
  if (typeof raw === 'string') {
    try {
      const arr = JSON.parse(raw) as unknown[]
      return arr
        .map(item => normalizeStep(item as Record<string, unknown>))
        .filter(Boolean)
        .map(s => migrateV1Step(s!)) as ProcessingStep[]
    } catch {
      return undefined
    }
  }
  if (Array.isArray(raw)) {
    const steps = raw
      .map(item => normalizeStep(item as Record<string, unknown>))
      .filter(Boolean)
      .map(s => migrateV1Step(s!)) as ProcessingStep[]
    return steps.length ? steps : undefined
  }
  return undefined
}

function mapDetail(raw: Record<string, unknown>): ConversationDetail {
  const messages = (raw.messages as Record<string, unknown>[] | undefined ?? []).map(m => ({
    id: String(m.id),
    role: m.role as 'user' | 'assistant',
    content: String(m.content ?? ''),
    reasoning: typeof m.reasoning === 'string' ? m.reasoning : undefined,
    steps: parseSteps(m.steps),
    status: m.status as string | undefined,
    intent: m.intent as string | undefined,
    seq: m.seq as number | undefined,
    createdAt: m.createdAt as string | undefined,
    executionPlanId: typeof m.executionPlanId === 'string' ? m.executionPlanId : undefined,
  }))
  return { ...mapSummary(raw), messages }
}

interface ApiResult<T> {
  code: number
  msg: string
  data: T
}

function isApiResult(raw: unknown): raw is ApiResult<unknown> {
  return typeof raw === 'object' && raw !== null && 'code' in raw && 'msg' in raw
}

/** BFF 成功时返回裸 JSON；GlobalExceptionHandler 失败时返回 { code, msg, data } 且 HTTP 仍为 200 */
function unwrapApiError(raw: unknown): unknown {
  if (!isApiResult(raw)) return raw
  if (raw.code !== 200) {
    throw new Error(raw.msg || '请求失败')
  }
  if (raw.data === null || raw.data === undefined) {
    throw new Error(raw.msg || '响应无数据')
  }
  return raw.data
}

function unwrapList(raw: unknown): Record<string, unknown>[] {
  const body = unwrapApiError(raw)
  if (!Array.isArray(body)) {
    throw new Error('对话列表格式异常')
  }
  return body as Record<string, unknown>[]
}

function unwrapObject(raw: unknown): Record<string, unknown> {
  const body = unwrapApiError(raw)
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    throw new Error('对话详情格式异常')
  }
  return body as Record<string, unknown>
}

export async function listConversations(): Promise<ConversationSummary[]> {
  const res = await fetch(`${API_BASE}/api/conversations`, { headers: apiHeaders() })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return unwrapList(await res.json()).map(mapSummary)
}

export async function createConversation(): Promise<ConversationSummary> {
  const res = await fetch(`${API_BASE}/api/conversations`, {
    method: 'POST',
    headers: apiHeaders(),
    body: '{}',
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return mapSummary(unwrapObject(await res.json()))
}

export async function getConversation(id: string): Promise<ConversationDetail> {
  if (!isValidConversationId(id)) {
    throw new Error('会话 id 无效')
  }
  const res = await fetch(`${API_BASE}/api/conversations/${id}`, { headers: apiHeaders() })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return mapDetail(unwrapObject(await res.json()))
}

export async function deleteConversation(id: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/conversations/${id}`, {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function updateConversationTitle(id: string, title: string): Promise<void> {
  const res = await fetch(`${API_BASE}/api/conversations/${id}`, {
    method: 'PATCH',
    headers: apiHeaders(),
    body: JSON.stringify({ title }),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}
