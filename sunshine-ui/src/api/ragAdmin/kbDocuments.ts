import type { TenantId } from '../tenants'
import { parseApiResponse } from '../apiError'
import { adminHeaders, ragApiBase } from './client'

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
  activeVersion: string | null
  chunkCount: number
}

export interface DocumentVersion {
  version: string
  status: string
  chunkCount: number
  hasContent: boolean
  needsQuarantineConfirm?: boolean
  ingestJobId?: number | null
  chunkStrategy?: string | null
  publishedAt: string | null
  createdAt: string | null
}

export type ChunkStrategy = 'markdown' | 'fixed' | 'recursive' | 'semantic' | 'parent_child'

export interface ChunkPreviewItem {
  index: number
  text: string
  charCount: number
  meta?: Record<string, unknown>
}

export interface ChunkPreviewResponse {
  previewId: string
  strategy: string
  params: Record<string, number>
  contentHash: string
  chunkCount: number
  chunks: ChunkPreviewItem[]
  expiresAt: string
}

export interface DocumentDetail {
  docId: string
  displayName: string
  sourceType: string
  activeVersion: string | null
  versions: DocumentVersion[]
}

export interface DocumentContentView {
  version: string
  content: string
  storagePath: string | null
}

export interface DocumentUploadResponse {
  async: boolean
  jobId: number | null
  version: string
  status: string
  progressPct: number | null
  content: string | null
  storagePath: string | null
}

export interface DocumentParseJobStatus {
  jobId: number
  docId: string
  version: string
  status: string
  progressPct: number | null
  progressPage: number | null
  totalPages: number | null
  confidence: number | null
  needsConfirm: boolean | null
  errorMsg: string | null
  updatedAt: string
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
  configMode?: string
  configVersionId?: number
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

export async function createDocument(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  displayName: string,
  sourceType: string = 'markdown',
): Promise<DocumentDetail> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify({ docId, displayName, sourceType }),
  })
  return parseApiResponse<DocumentDetail>(res)
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

export async function updateDocument(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  displayName: string,
): Promise<DocumentDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}`,
    {
      method: 'PUT',
      headers: adminHeaders(tenantId),
      body: JSON.stringify({ displayName }),
    },
  )
  return parseApiResponse<DocumentDetail>(res)
}

export async function deleteDocument(
  tenantId: TenantId,
  kbId: string,
  docId: string,
): Promise<void> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}`,
    { method: 'DELETE', headers: adminHeaders(tenantId) },
  )
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function listChunks(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  version?: string,
  store: 'milvus' | 'es' = 'milvus',
): Promise<ChunkPreview[]> {
  const params = new URLSearchParams({ store })
  if (version != null) params.set('version', String(version))
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/chunks?${params}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<ChunkPreview[]>(res)
}

export async function getDocumentContent(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  version: string,
): Promise<DocumentContentView> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/versions/${encodeURIComponent(version)}/content`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<DocumentContentView>(res)
}

export async function saveDocumentContent(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  version: string,
  content: string,
): Promise<DocumentContentView> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/versions/${encodeURIComponent(version)}/content`,
    {
      method: 'PUT',
      headers: adminHeaders(tenantId),
      body: JSON.stringify({ content }),
    },
  )
  return parseApiResponse<DocumentContentView>(res)
}

export async function uploadDocumentFile(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  file: File,
): Promise<DocumentUploadResponse> {
  const form = new FormData()
  form.append('file', file)
  const headers = adminHeaders(tenantId)
  delete headers['Content-Type']
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/upload`,
    { method: 'POST', headers, body: form },
  )
  return parseApiResponse<DocumentUploadResponse>(res)
}

export async function getDocumentParseJob(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  jobId: number,
): Promise<DocumentParseJobStatus> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/parse-jobs/${jobId}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<DocumentParseJobStatus>(res)
}

export async function confirmDocumentParseJob(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  jobId: number,
): Promise<void> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/parse-jobs/${jobId}/confirm`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function previewChunks(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  body: { version?: string; strategy: ChunkStrategy; params: Record<string, number> },
): Promise<ChunkPreviewResponse> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/chunk-preview`,
    {
      method: 'POST',
      headers: adminHeaders(tenantId),
      body: JSON.stringify(body),
    },
  )
  return parseApiResponse<ChunkPreviewResponse>(res)
}

export async function publishDocument(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  body: { previewId: string },
): Promise<{ docId: string; docName: string; version: string; chunks: number }> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/publish`,
    {
      method: 'POST',
      headers: adminHeaders(tenantId),
      body: JSON.stringify(body),
    },
  )
  return parseApiResponse(res)
}

export async function forkDocumentVersion(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  version: string,
): Promise<DocumentDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/versions/${encodeURIComponent(version)}/fork`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<DocumentDetail>(res)
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
