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
}

export interface ConversationDetail extends ConversationSummary {
  messages: ConversationMessage[]
}

function toTimestamp(iso: string | undefined): number {
  if (!iso) return Date.now()
  const t = Date.parse(iso)
  return Number.isNaN(t) ? Date.now() : t
}

function mapSummary(raw: Record<string, unknown>): ConversationSummary {
  return {
    id: String(raw.id),
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
  }))
  return { ...mapSummary(raw), messages }
}

export async function listConversations(): Promise<ConversationSummary[]> {
  const res = await fetch(`${API_BASE}/api/conversations`, { headers: apiHeaders() })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const data = await res.json()
  return (data as Record<string, unknown>[]).map(mapSummary)
}

export async function createConversation(): Promise<ConversationSummary> {
  const res = await fetch(`${API_BASE}/api/conversations`, {
    method: 'POST',
    headers: apiHeaders(),
    body: '{}',
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return mapSummary(await res.json())
}

export async function getConversation(id: string): Promise<ConversationDetail> {
  const res = await fetch(`${API_BASE}/api/conversations/${id}`, { headers: apiHeaders() })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return mapDetail(await res.json())
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
