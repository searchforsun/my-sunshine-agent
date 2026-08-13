import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface L2StateEntry {
  id: string
  userId: string
  tenantId: string
  kind: string
  stateKey: string
  stateValue: string
  confidence: number
  status: string
  expiresAt?: string | null
  sourceMsgId?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface L2UpdatePayload {
  stateValue?: string
  confidence?: number
  status?: string
}

export interface L1WindowRow {
  band: 'near' | 'mid' | 'far' | string
  index: number
  userText?: string | null
  assistantText?: string | null
  assistantSummarized?: boolean
  at?: string | null
}

export interface L1Snapshot {
  convId: string
  userId: string
  tenantId: string
  midAnswers: Record<string, string>
  farSummary: string
  farFoldedMsgIds: string[]
  nearN: number
  midN: number
  updatedAt?: string
  rows?: L1WindowRow[]
}

export interface L3Status {
  userId: string
  tenantId: string
  contextEnabled: boolean
  collection: string
  note?: string
  l1RowCount: number
  l3TopK: number
  l3MinScore: number
}

export interface L3Entry {
  msgId: string
  role: string
  chunkIndex: number
  content: string
  createdAt?: string | null
  expiresAt?: string | null
}

export interface GcResult {
  ok: boolean
  message: string
}

export interface ReingestResult {
  convId: string
  ingested: number
  message: string
}

export interface ConversationSummary {
  id: string
  title: string
  kind?: string
  workspaceId?: string | null
  checkoutPath?: string | null
  createdAt?: string
  updatedAt?: string
}

export async function listContextConversations(
  userId: string,
  tenantId = 'default',
): Promise<ConversationSummary[]> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(apiUrl(`/api/admin/context/conversations?${q}`), { headers: apiHeaders() })
  return parseApiResponse<ConversationSummary[]>(res)
}

export async function listContextL2(userId: string, tenantId = 'default'): Promise<L2StateEntry[]> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(apiUrl(`/api/admin/context/l2?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L2StateEntry[]>(res)
}

export async function updateContextL2(id: string, body: L2UpdatePayload): Promise<L2StateEntry> {
  const res = await fetch(apiUrl(`/api/admin/context/l2/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<L2StateEntry>(res)
}

export async function voidContextL2(id: string): Promise<L2StateEntry> {
  const res = await fetch(apiUrl(`/api/admin/context/l2/${encodeURIComponent(id)}/void`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<L2StateEntry>(res)
}

export async function getContextL1(convId: string): Promise<L1Snapshot> {
  const q = new URLSearchParams({ convId })
  const res = await fetch(apiUrl(`/api/admin/context/l1?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L1Snapshot>(res)
}

export async function getContextL3Status(userId: string, tenantId = 'default'): Promise<L3Status> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(apiUrl(`/api/admin/context/l3/status?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L3Status>(res)
}

export async function listContextL3Entries(convId: string): Promise<L3Entry[]> {
  const q = new URLSearchParams({ convId })
  const res = await fetch(apiUrl(`/api/admin/context/l3/entries?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L3Entry[]>(res)
}

export async function runContextL3Gc(): Promise<GcResult> {
  const res = await fetch(apiUrl('/api/admin/context/l3/gc'), {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<GcResult>(res)
}

export async function reingestContextL3(convId: string): Promise<ReingestResult> {
  const q = new URLSearchParams({ convId })
  const res = await fetch(apiUrl(`/api/admin/context/l3/reingest?${q}`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<ReingestResult>(res)
}
