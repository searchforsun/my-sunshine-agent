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
  publishedAt: string | null
  createdAt: string | null
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

/** @deprecated 使用 uploadDocumentFile */
export const uploadDocumentMarkdown = uploadDocumentFile

export async function publishDocument(
  tenantId: TenantId,
  kbId: string,
  docId: string,
  version: string,
): Promise<{ docId: string; docName: string; version: string; chunks: number }> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/documents/${encodeURIComponent(docId)}/publish?version=${encodeURIComponent(version)}`,
    { method: 'POST', headers: adminHeaders(tenantId) },
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

export async function ingestText(
  tenantId: TenantId,
  kbId: string,
  content: string,
  docName?: string,
  docId?: string,
): Promise<{ docId: string; docName: string; version: string; chunks: number }> {
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

export interface ConfigFieldSchema {
  fieldId: string
  label: string
  type: string
  min?: number | null
  max?: number | null
  scope: string
  currentValue: unknown
  enumValues?: string[] | null
}

export interface ConfigScopeGroup {
  scope: string
  label: string
  dataId: string
  nacosPath: string
  fields: ConfigFieldSchema[]
}

export interface EffectiveRagConfig {
  minScore: number
  strategy: string
  rrfK: number
  hybridPoolSize: number
  rerankMinScore: number
  chunkMaxSize: number
}

export interface ConfigSchemaResponse {
  scopes: ConfigScopeGroup[]
  effective: EffectiveRagConfig
}

export interface FailedEvalSample {
  queryId: string
  query: string
  expectedDocNames: string[]
  topDocNames: string[]
}

export interface PublishGateFailure {
  recallAt5: number
  baselineRecallAt5: number
  failedSamples: FailedEvalSample[]
}

export interface ConfigBundleDraftView {
  draftVersionId: number
  draftVersionNo: number
  payload: Record<string, unknown>
  activePublishedVersionId: number | null
  activePublishedVersionNo: number | null
}

export interface ConfigVersionSummary {
  id: number
  versionNo: number
  status: string
  createdAt: string
  publishedAt: string | null
  active: boolean
  recallAt5: number | null
  changeNote: string | null
  createdBy: string | null
}

export interface SubmitEvalResult {
  versionId: number
  versionNo: number
  status: string
}

export interface PublishBundleResult {
  versionId: number
  versionNo: number
  eval: { recallAt5: number; baselineRecallAt5: number; passedGate: boolean; failedSamples: FailedEvalSample[] }
  reportId: number
}

export interface ConfigSuggestionItem {
  path: string
  current?: unknown
  proposed: unknown
  reason?: string
}

export interface TextSuggestionItem {
  target: string
  kind: string
  current?: string
  proposed: string
  reason?: string
}

export interface EvalSuggestResult {
  diagnosis: string
  suggestions: ConfigSuggestionItem[]
  textSuggestions?: TextSuggestionItem[]
}

export interface EvalSuiteSummary {
  id: number
  suiteKey: string
  displayName: string
  kind: string
  format: string
  itemCount: number
  status: string
  builtin: boolean
  createdAt: string
}

export interface EvalSuiteItemView {
  itemKey: string
  sortOrder: number
  queryText: string
  itemType: string
  relevantDocIds: string[]
  relevantKeywords: string[]
  category: string | null
  expectEmpty: boolean
}

export interface EvalSuiteDetail extends EvalSuiteSummary {
  description: string | null
  contentRef: string | null
  hooks: Record<string, unknown>
  config: Record<string, unknown>
  content: string | null
  items: EvalSuiteItemView[]
}

export interface EvalSuiteCreateRequest {
  suiteKey: string
  displayName?: string
  description?: string
  kind?: string
  config?: Record<string, unknown>
  hooks?: Record<string, unknown>
  content?: string
}

export interface EvalSuiteUpdateRequest {
  displayName?: string
  description?: string
  config?: Record<string, unknown>
  hooks?: Record<string, unknown>
}

export interface EvalSuiteQueryRequest {
  action: 'add' | 'update' | 'delete'
  id?: string
  query?: string
  relevantDocIds?: string[]
  category?: string
}

export interface EvalJobSummary {
  jobId: number
  kbId: string
  suite: string
  suiteKey: string
  status: string
  configVersionId: number | null
  configVersionNo: number | null
  reportId: number | null
  recallAt5: number | null
  passedGate: boolean | null
  createdAt: string
  finishedAt: string | null
}

export class PublishGateError extends Error {
  readonly gate: PublishGateFailure

  constructor(gate: PublishGateFailure, message = 'publish gate failed') {
    super(message)
    this.name = 'PublishGateError'
    this.gate = gate
  }
}

export class ConfigVersionConflictError extends Error {
  readonly code: number

  constructor(message: string, code = 409) {
    super(message)
    this.name = 'ConfigVersionConflictError'
    this.code = code
  }
}

export async function fetchKbConfigSchema(tenantId: TenantId, kbId: string): Promise<ConfigSchemaResponse> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/schema`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<ConfigSchemaResponse>(res)
}

export async function fetchKbConfigDraft(tenantId: TenantId, kbId: string): Promise<ConfigBundleDraftView> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/draft`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<ConfigBundleDraftView>(res)
}

export async function saveKbConfigDraft(
  tenantId: TenantId,
  kbId: string,
  payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/draft`, {
    method: 'PUT',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(payload),
  })
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function publishKbConfigBundle(tenantId: TenantId, kbId: string): Promise<SubmitEvalResult> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/publish`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
  })
  const raw = (await res.json()) as { code: number; msg: string; data?: SubmitEvalResult }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    const { ApiError: Err, resolveApiMessage } = await import('./apiError')
    throw new Err(resolveApiMessage(raw.code, raw.msg, '提交评测失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as SubmitEvalResult
}

export async function listKbConfigVersions(tenantId: TenantId, kbId: string): Promise<ConfigVersionSummary[]> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<ConfigVersionSummary[]>(res)
}

export async function activateKbConfigVersion(
  tenantId: TenantId,
  kbId: string,
  versionId: number,
): Promise<PublishBundleResult> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions/${versionId}/activate`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  const raw = (await res.json()) as { code: number; msg: string; data?: PublishBundleResult | PublishGateFailure }
  if (raw.code === 422 && raw.data) {
    throw new PublishGateError(raw.data as PublishGateFailure, raw.msg)
  }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    const { ApiError: Err, resolveApiMessage } = await import('./apiError')
    throw new Err(resolveApiMessage(raw.code, raw.msg, '生效失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as PublishBundleResult
}

export async function fetchKbConfigEffective(
  tenantId: TenantId,
  kbId: string,
  mode: 'published' | 'draft' | 'version',
  versionId?: number,
): Promise<Record<string, unknown>> {
  const qs = new URLSearchParams({ mode })
  if (versionId != null) {
    qs.set('versionId', String(versionId))
  }
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/effective?${qs}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function forkKbConfigVersion(
  tenantId: TenantId,
  kbId: string,
  versionId: number,
): Promise<Record<string, unknown>> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions/${versionId}/fork`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  const raw = (await res.json()) as { code: number; msg: string; data?: Record<string, unknown> }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    const { ApiError: Err, resolveApiMessage } = await import('./apiError')
    throw new Err(resolveApiMessage(raw.code, raw.msg, '复制草稿失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as Record<string, unknown>
}

export async function revertKbConfigVersionToDraft(
  tenantId: TenantId,
  kbId: string,
  versionId: number,
): Promise<Record<string, unknown>> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions/${versionId}/revert-to-draft`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  const raw = (await res.json()) as { code: number; msg: string; data?: Record<string, unknown> }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    const { ApiError: Err, resolveApiMessage } = await import('./apiError')
    throw new Err(resolveApiMessage(raw.code, raw.msg, '转为草稿失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as Record<string, unknown>
}

export async function applyConfigSuggestions(
  tenantId: TenantId,
  kbId: string,
  suggestions: ConfigSuggestionItem[],
  versionId?: number | null,
): Promise<Record<string, unknown>> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/draft/apply-suggestions`,
    {
      method: 'POST',
      headers: adminHeaders(tenantId),
      body: JSON.stringify({ suggestions, versionId: versionId ?? undefined }),
    },
  )
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function putKbConfigOverride(
  tenantId: TenantId,
  kbId: string,
  patch: Record<string, unknown>,
): Promise<EffectiveRagConfig> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/override`, {
    method: 'PUT',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(patch),
  })
  return parseApiResponse<EffectiveRagConfig>(res)
}

export interface EvalJobStatus {
  jobId: number
  tenantId: string
  kbId: string
  suite: string
  status: string
  reportId: number | null
  configVersionId: number | null
  totalItems: number | null
  processedItems: number | null
  progressPct: number | null
  createdAt: string
  finishedAt: string | null
}

export interface EvalReportView {
  reportId: number
  jobId: number
  recallAt5: number | null
  mrr: number | null
  passedGate: boolean | null
  baselineRecallAt5: number | null
  summary: Record<string, unknown>
  failedSamples?: Array<Record<string, unknown>>
  suggestions?: EvalSuggestResult | null
  reportMdPath: string | null
  reportJsonPath: string | null
}

export async function listEvalSuites(tenantId: TenantId): Promise<EvalSuiteSummary[]> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suites`, { headers: adminHeaders(tenantId) })
  return parseApiResponse<EvalSuiteSummary[]>(res)
}

export async function getEvalSuite(tenantId: TenantId, suiteKey: string): Promise<EvalSuiteDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function createEvalSuite(
  tenantId: TenantId,
  body: EvalSuiteCreateRequest,
): Promise<EvalSuiteDetail> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suites`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(body),
  })
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function updateEvalSuite(
  tenantId: TenantId,
  suiteKey: string,
  body: EvalSuiteUpdateRequest,
): Promise<EvalSuiteDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}`,
    {
      method: 'PUT',
      headers: adminHeaders(tenantId),
      body: JSON.stringify(body),
    },
  )
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function deleteEvalSuite(tenantId: TenantId, suiteKey: string): Promise<void> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}`,
    { method: 'DELETE', headers: adminHeaders(tenantId) },
  )
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function mutateEvalSuiteQuery(
  tenantId: TenantId,
  suiteKey: string,
  body: EvalSuiteQueryRequest,
): Promise<EvalSuiteDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}/queries`,
    { method: 'POST', headers: adminHeaders(tenantId), body: JSON.stringify(body) },
  )
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function ensureKbCustomEvalSuite(
  tenantId: TenantId,
  kbId: string,
  displayName?: string,
): Promise<EvalSuiteDetail> {
  const qs = new URLSearchParams({ kbId })
  if (displayName) qs.set('displayName', displayName)
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suites/kb-custom/ensure?${qs}`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function listEvalJobs(tenantId: TenantId, kbId: string, limit = 20): Promise<EvalJobSummary[]> {
  const qs = new URLSearchParams({ kbId, limit: String(limit) })
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/jobs?${qs}`, { headers: adminHeaders(tenantId) })
  return parseApiResponse<EvalJobSummary[]>(res)
}

export async function suggestEvalFix(
  tenantId: TenantId,
  body: { reportId: number; kbId?: string; regenerate?: boolean },
): Promise<EvalSuggestResult> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suggest`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(body),
  })
  return parseApiResponse<EvalSuggestResult>(res)
}

export async function runEval(
  tenantId: TenantId,
  body: {
    suiteKey?: string
    kbId?: string
    strategy?: string
    configMode?: string
    configVersionId?: number
  },
): Promise<EvalJobStatus> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/run`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(body),
  })
  const raw = (await res.json()) as { code: number; msg: string; data?: EvalJobStatus }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '当前配置正在评测中，请稍后')
  }
  if (!res.ok || raw.code !== 200) {
    const { ApiError: Err, resolveApiMessage } = await import('./apiError')
    throw new Err(resolveApiMessage(raw.code, raw.msg, '运行评测失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as EvalJobStatus
}

export async function getEvalJob(tenantId: TenantId, jobId: number): Promise<EvalJobStatus> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/jobs/${jobId}`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<EvalJobStatus>(res)
}

export async function getEvalReport(tenantId: TenantId, reportId: number): Promise<EvalReportView> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/reports/${reportId}`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<EvalReportView>(res)
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
