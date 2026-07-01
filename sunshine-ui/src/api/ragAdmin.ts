import type { TenantId } from './tenants'
import { resolveApiBase } from './config'
import { apiHeaders } from '../stores/authStore'
import { parseApiResponse } from './apiError'

const ADMIN_TOKEN = import.meta.env.VITE_RAG_ADMIN_TOKEN ?? 'sunshine-rag-admin-dev'

function ragApiBase(): string {
  const configured = import.meta.env.VITE_RAG_API_BASE?.trim()
  if (configured) return configured.replace(/\/$/, '')
  return resolveApiBase()
}

function adminHeaders(tenantId: TenantId): Record<string, string> {
  const tid = tenantId.trim() || 'default'
  return {
    ...apiHeaders(),
    'x-tenant-id': tid,
    'X-Admin-Token': ADMIN_TOKEN,
  }
}

function ragHeaders(tenantId: TenantId): Record<string, string> {
  const tid = tenantId.trim() || 'default'
  return {
    ...apiHeaders(),
    'x-tenant-id': tid,
  }
}

export interface KnowledgeBase {
  kbId: string
  displayName: string
  description: string | null
  isDefault: boolean
  status: string
}

export interface KbDocument {
  docId: string
  displayName: string
  sourceType: string
  activeVersion: number
  chunkCount: number
}

export interface DocumentVersion {
  version: number
  status: string
  chunkCount: number
  publishedAt: string | null
}

export interface DocumentDetail {
  docId: string
  displayName: string
  sourceType: string
  versions: DocumentVersion[]
}

export interface ChunkPreview {
  chunkIndex: number
  docName: string
  content: string
}

export interface DebugCandidate {
  docName: string
  content: string
  score: number
  source: string
}

export interface DebugStage {
  name: string
  latencyMs: number
  applied?: boolean
  from?: string
  to?: string
  scenarioLabel?: string
  candidates?: DebugCandidate[]
  dropped?: DebugCandidate[]
}

export interface DebugSearchRequest {
  query: string
  topK?: number
  strategy?: string
  includeRewrite?: boolean
  overrides?: Record<string, unknown>
}

export interface DebugSearchResponse {
  stages: DebugStage[]
  final: Array<{ docName: string; content: string; score: number }>
}

export async function listKbs(tenantId: TenantId): Promise<KnowledgeBase[]> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs`, { headers: adminHeaders(tenantId) })
  return parseApiResponse<KnowledgeBase[]>(res)
}

export async function createKb(
  tenantId: TenantId,
  kbId: string,
  displayName: string,
  description?: string,
): Promise<KnowledgeBase> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify({ kbId, displayName, description: description ?? null }),
  })
  return parseApiResponse<KnowledgeBase>(res)
}

export async function listDocuments(tenantId: TenantId, kbId: string): Promise<KbDocument[]> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<KbDocument[]>(res)
}

export async function getDocument(
  tenantId: TenantId,
  kbId: string,
  docId: string,
): Promise<DocumentDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<DocumentDetail>(res)
}

export async function listChunks(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  version?: number,
): Promise<ChunkPreview[]> {
  const qs = version != null ? `?version=${version}` : ''
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/chunks${qs}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<ChunkPreview[]>(res)
}

export async function ingestText(
  tenantId: TenantId,
  kbId: string,
  content: string,
  docName?: string,
  docId?: string,
): Promise<{ docId: string; docName: string; version: number; chunks: number }> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/ingest/text`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify({ content, docName, docId, displayName: docName }),
  })
  return parseApiResponse(res)
}

export async function debugSearch(
  tenantId: TenantId,
  kbId: string,
  body: DebugSearchRequest,
): Promise<DebugSearchResponse> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/search/debug`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify({ ...body, kbId }),
  })
  return parseApiResponse<DebugSearchResponse>(res)
}

/** 公开检索（非 admin） */
export async function searchKnowledgePublic(
  query: string,
  tenantId: TenantId,
  kbId: string,
  topK = 5,
): Promise<Array<{ docName: string; content: string; score: number }>> {
  const res = await fetch(`${ragApiBase()}/api/rag/search`, {
    method: 'POST',
    headers: ragHeaders(tenantId),
    body: JSON.stringify({ query, topK, kbId }),
  })
  const data = await parseApiResponse<{ results?: Array<{ docName: string; content: string; score: number }> }>(res)
  return Array.isArray(data.results) ? data.results : []
}
