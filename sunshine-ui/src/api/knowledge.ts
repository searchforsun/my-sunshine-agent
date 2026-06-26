import type { TenantId } from './tenants'
import { parseApiResponse } from './apiError'

const API_BASE = import.meta.env.VITE_RAG_API_BASE ?? 'http://localhost:8400'
function ragHeaders(tenantId: TenantId): Record<string, string> {
  const tid = tenantId.trim() || 'default'
  return {
    'Content-Type': 'application/json',
    'x-tenant-id': tid,
  }
}

export async function uploadDocument(
  content: string,
  tenantId: TenantId,
  docName?: string,
): Promise<{ chunks: number; docName?: string }> {
  const response = await fetch(`${API_BASE}/api/rag/documents`, {
    method: 'POST',
    headers: ragHeaders(tenantId),
    body: JSON.stringify({ content, docName }),
  })
  const data = await parseApiResponse<{ chunks?: number; docName?: string }>(response)
  return { chunks: Number(data.chunks ?? 0), docName: data.docName }
}

export interface KnowledgeHit {
  docName: string
  content: string
  score: number
}

export async function searchKnowledge(
  query: string,
  tenantId: TenantId,
  topK = 5,
): Promise<KnowledgeHit[]> {
  const response = await fetch(`${API_BASE}/api/rag/search`, {
    method: 'POST',
    headers: ragHeaders(tenantId),
    body: JSON.stringify({ query, topK }),
  })
  const data = await parseApiResponse<{ results?: KnowledgeHit[] }>(response)
  return Array.isArray(data.results) ? data.results : []
}
