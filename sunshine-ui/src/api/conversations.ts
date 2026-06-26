import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import type { ExecutionPreference } from './executionModes'
import { isExecutionPreference } from './executionModes'
import type { ProcessingStep } from './processingSteps'
import { migrateV1Step, normalizeStep } from './processingSteps'
import { ApiError, parseBffPayload } from './apiError'

const API_BASE = () => resolveApiBase()
export interface ConversationSummary {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  executionPreference?: ExecutionPreference
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
    executionPreference: isExecutionPreference(m.executionPreference) ? m.executionPreference : undefined,
  }))
  return { ...mapSummary(raw), messages }
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

export async function createConversation(): Promise<ConversationSummary> {
  const res = await fetch(`${API_BASE()}/api/conversations`, {
    method: 'POST',
    headers: apiHeaders(),
    body: '{}',
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
