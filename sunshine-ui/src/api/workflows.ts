import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface WorkflowEntry {
  id: string
  displayName: string
  description: string
  enabled: boolean
  activeVersion: number
  source: string
  updatedAt?: string
  activeVersionCreatedAt?: string
  activeVersionPublished?: boolean
}

export interface WorkflowVersion {
  id: number
  workflowId: string
  version: number
  status: string
  createdAt?: string
  publishedAt?: string
}

export interface WorkflowCatalogEntry {
  id: string
  mode: string
  displayName: string
  description: string
  examples: string[]
  nodes: string[]
}

export interface WorkflowPlanInputBinding {
  name: string
  source: string
  /** string | number | boolean | object | array（小写；缺省按 string） */
  type?: string
  required?: boolean
}

export interface WorkflowPlanNode {
  id: string
  type: string
  displayName?: string
  params?: Record<string, unknown>
  /** 显式输入绑定（WF-1 结构化 I/O）：业务入参由 inputs 承载，params 仅保留控制参数 */
  inputs?: WorkflowPlanInputBinding[]
  /** loop 容器内 body 归属 */
  parentId?: string
}

export interface WorkflowPlanEdgeCondition {
  left: string
  op: string
  right?: string
}

export interface WorkflowPlanEdgeConditionGroup {
  logic: 'and' | 'or'
  items: WorkflowPlanEdgeCondition[]
}

export interface WorkflowPlanEdge {
  from: string
  to: string
  /** 复合条件（新格式 {logic, items}）；兼容旧 {left, op, right} */
  condition?: WorkflowPlanEdgeConditionGroup | WorkflowPlanEdgeCondition
  default?: boolean
}

export interface WorkflowPlan {
  planId: string | null
  reason: string
  nodes: WorkflowPlanNode[]
  edges: WorkflowPlanEdge[]
  /** Studio 画布节点坐标；loop 可带 width/height */
  layout?: Record<string, { x: number; y: number; width?: number; height?: number }>
}

export interface WorkflowNodeRetryDefaults {
  maxAttempts: number
  backoffMs: number
  onFailure: string
}

export interface WorkflowNodeDefaultsResponse {
  defaults: WorkflowNodeRetryDefaults
  byType: Record<string, WorkflowNodeRetryDefaults>
  criticalOnFailure?: string
  backoffMultiplier?: number
  retryOnErrorClass?: string[]
  catalog?: { intentAfter: string }
  nodeParams?: Record<string, { topK?: number; kbIdEmptyLabel?: string; maxIters?: number }>
}

export interface WorkflowEditable {
  workflowId: string
  version: number
  status: string
  plan: WorkflowPlan
  catalog: Record<string, unknown>
}

function normalizeCatalog(raw: Record<string, unknown> | undefined): Record<string, unknown> {
  if (!raw) return {}
  if (raw.catalog && typeof raw.catalog === 'object') return raw.catalog as Record<string, unknown>
  if (raw.catalogMeta && typeof raw.catalogMeta === 'object') return raw.catalogMeta as Record<string, unknown>
  return raw
}

export async function listWorkflows(): Promise<WorkflowEntry[]> {
  const res = await fetch(apiUrl('/api/workflows'), { headers: apiHeaders() })
  return parseApiResponse<WorkflowEntry[]>(res)
}

export async function listWorkflowCatalog(): Promise<WorkflowCatalogEntry[]> {
  const res = await fetch(apiUrl('/api/workflows/catalog'), { headers: apiHeaders() })
  return parseApiResponse<WorkflowCatalogEntry[]>(res)
}

export async function fetchWorkflowNodeDefaults(): Promise<WorkflowNodeDefaultsResponse> {
  const res = await fetch(apiUrl('/api/workflows/node-defaults'), { headers: apiHeaders() })
  return parseApiResponse<WorkflowNodeDefaultsResponse>(res)
}

export async function listWorkflowVersions(id: string): Promise<WorkflowVersion[]> {
  const res = await fetch(apiUrl(`/api/workflows/${encodeURIComponent(id)}/versions`), {
    headers: apiHeaders(),
  })
  return parseApiResponse<WorkflowVersion[]>(res)
}

export async function getWorkflowVersion(id: string, version: number): Promise<WorkflowEditable> {
  const res = await fetch(
    apiUrl(`/api/workflows/${encodeURIComponent(id)}/versions/${version}`),
    { headers: apiHeaders() },
  )
  const data = await parseApiResponse<WorkflowEditable & { catalogMeta?: Record<string, unknown> }>(res)
  return { ...data, catalog: normalizeCatalog(data.catalog ?? data.catalogMeta) }
}

export async function getWorkflowEditable(id: string): Promise<WorkflowEditable> {
  const res = await fetch(apiUrl(`/api/workflows/${encodeURIComponent(id)}/editable`), {
    headers: apiHeaders(),
  })
  const data = await parseApiResponse<WorkflowEditable & { catalogMeta?: Record<string, unknown> }>(res)
  return { ...data, catalog: normalizeCatalog(data.catalog ?? data.catalogMeta) }
}

export async function createWorkflow(
  id: string,
  displayName: string,
  description: string,
): Promise<WorkflowEntry> {
  const res = await fetch(apiUrl('/api/workflows'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, displayName, description }),
  })
  return parseApiResponse<WorkflowEntry>(res)
}

export async function updateWorkflow(
  id: string,
  displayName: string,
  description: string,
): Promise<WorkflowEntry> {
  const res = await fetch(apiUrl(`/api/workflows/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ displayName, description }),
  })
  return parseApiResponse<WorkflowEntry>(res)
}

export async function setWorkflowEnabled(id: string, enabled: boolean): Promise<WorkflowEntry> {
  const res = await fetch(apiUrl(`/api/workflows/${encodeURIComponent(id)}/enable`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  return parseApiResponse<WorkflowEntry>(res)
}

export interface WorkflowPlanValidation {
  valid: boolean
  issues: string[]
}

export async function validateWorkflowPlan(plan: WorkflowPlan): Promise<WorkflowPlanValidation> {
  const res = await fetch(apiUrl('/api/workflows/plan/validate'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ plan }),
  })
  return parseApiResponse<WorkflowPlanValidation>(res)
}

export async function saveWorkflowDraft(
  id: string,
  plan: WorkflowPlan,
  catalog: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(apiUrl(`/api/workflows/${encodeURIComponent(id)}/draft`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ plan, catalog }),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export interface WorkflowPublished {
  workflowId: string
  version: number
  plan: WorkflowPlan
  catalog: Record<string, unknown>
}

export async function publishWorkflow(id: string, version?: number): Promise<WorkflowPublished> {
  const qs = version != null ? `?version=${version}` : ''
  const res = await fetch(apiUrl(`/api/workflows/${encodeURIComponent(id)}/publish${qs}`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  const data = await parseApiResponse<WorkflowPublished & { catalogMeta?: Record<string, unknown> }>(res)
  return {
    workflowId: data.workflowId,
    version: data.version,
    plan: data.plan,
    catalog: normalizeCatalog(data.catalog ?? data.catalogMeta),
  }
}

export async function forkWorkflowVersion(id: string, version: number): Promise<WorkflowEntry> {
  const res = await fetch(
    apiUrl(`/api/workflows/${encodeURIComponent(id)}/versions/${version}/fork`),
    { method: 'POST', headers: apiHeaders() },
  )
  return parseApiResponse<WorkflowEntry>(res)
}

export async function deleteWorkflow(id: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/workflows/${encodeURIComponent(id)}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function deleteWorkflowVersion(id: string, version: number): Promise<WorkflowEntry> {
  const res = await fetch(
    apiUrl(`/api/workflows/${encodeURIComponent(id)}/versions/${version}`),
    { method: 'DELETE', headers: apiHeaders() },
  )
  return parseApiResponse<WorkflowEntry>(res)
}

export async function exportWorkflowVersion(id: string, version: number): Promise<Record<string, unknown>> {
  const res = await fetch(
    apiUrl(`/api/workflows/${encodeURIComponent(id)}/versions/${version}/export`),
    { headers: apiHeaders() },
  )
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function importWorkflowPackage(body: Record<string, unknown>): Promise<WorkflowEntry> {
  const res = await fetch(apiUrl('/api/workflows/import'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<WorkflowEntry>(res)
}
