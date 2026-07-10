import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse, throwIfHttpError } from './apiError'
import type { TenantId } from './tenants'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface SdkApplication {
  id: string
  nacosService: string
  displayName: string | null
  catalogPath: string
  invokePath: string
  tenantId: string
  status: string
  lastSeenAt: string | null
  schemaVersion: number
  createdAt: string
  updatedAt: string
}

export interface McpServer {
  id: string
  displayName: string | null
  transport: string
  command: string | null
  argsJson: string | null
  endpoint: string | null
  envJson: string | null
  tenantId: string
  enabled: boolean
  lastProbeAt: string | null
  probeStatus: string | null
  probeError: string | null
  createdAt: string
  updatedAt: string
}

export interface ToolCatalogEntry {
  id: string
  displayName: string
  description: string
  kind: string
  timelinePhase: string
  outputSummaryKind: string
  parameters: Record<string, unknown>
  sideEffect: string
  requireConfirmation: boolean
  idValid?: boolean
  idError?: string | null
}

export interface ToolDefinition {
  id: string
  source: string
  sourceRef: string
  externalName: string
  displayName: string
  description: string | null
  schemaJson: Record<string, unknown>
  schemaHash: string | null
  kind: string
  timelinePhase: string
  outputSummaryKind: string
  sideEffect: string
  tenantId: string
  enabled: boolean
  metadataEdited: boolean
  discoveredAt: string | null
  updatedAt: string
}

export interface ToolSetConfig {
  toolIds: string[]
}

export interface ToolPatchBody {
  enabled?: boolean
  displayName?: string
  description?: string
  requireConfirmation?: boolean
}

export interface PlanWorkflowNodeDefaults {
  maxAttempts: number
  backoffMs: number
  backoffMultiplier: number
  onFailure: string
  retryOnErrorClass: string[]
}

export interface PlanWorkflowNodeTypeOverride {
  maxAttempts?: number | null
  onFailure?: string | null
}

export interface PlanWorkflowExecutionPolicy {
  criticalOnFailure: string
  defaults: PlanWorkflowNodeDefaults
  byType: Record<string, PlanWorkflowNodeTypeOverride>
}

export interface McpServerCreateBody {
  id: string
  displayName?: string
  transport: string
  command?: string
  argsJson?: string
  endpoint?: string
  envJson?: string
  tenantId?: string
  enabled?: boolean
}

export interface McpServerPatchBody {
  displayName?: string
  transport?: string
  command?: string
  argsJson?: string
  endpoint?: string
  envJson?: string
  enabled?: boolean
}

/** 按 SDK 应用 ID 前缀过滤 Catalog 工具（id 形如 sdk__{appId}__{name}） */
export function filterSdkTools(catalog: ToolCatalogEntry[], appId: string): ToolCatalogEntry[] {
  const prefix = `sdk__${appId}__`
  return catalog.filter(t => t.id.startsWith(prefix))
}

export async function listSdkApplications(): Promise<SdkApplication[]> {
  const res = await fetch(apiUrl('/api/admin/tools/sdk-applications'), { headers: apiHeaders() })
  return parseApiResponse<SdkApplication[]>(res)
}

export async function syncSdkApplication(id: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/admin/tools/sdk-applications/${encodeURIComponent(id)}/sync`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}

export async function listMcpServers(): Promise<McpServer[]> {
  const res = await fetch(apiUrl('/api/admin/mcp/servers'), { headers: apiHeaders() })
  return parseApiResponse<McpServer[]>(res)
}

export async function createMcpServer(body: McpServerCreateBody): Promise<McpServer> {
  const res = await fetch(apiUrl('/api/admin/mcp/servers'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<McpServer>(res)
}

export async function importMcpServers(rawJson: string): Promise<McpServer[]> {
  const res = await fetch(apiUrl('/api/admin/mcp/servers/import'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: rawJson,
  })
  return parseApiResponse<McpServer[]>(res)
}

export async function exportMcpServers(): Promise<string> {
  const res = await fetch(apiUrl('/api/admin/mcp/servers/export'), { headers: apiHeaders() })
  await throwIfHttpError(res)
  return res.text()
}

export async function probeMcpServer(id: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/admin/mcp/servers/${encodeURIComponent(id)}/probe`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}

export async function updateMcpServer(id: string, body: McpServerPatchBody): Promise<McpServer> {
  const res = await fetch(apiUrl(`/api/admin/mcp/servers/${encodeURIComponent(id)}`), {
    method: 'PATCH',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<McpServer>(res)
}

export async function deleteMcpServer(id: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/admin/mcp/servers/${encodeURIComponent(id)}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}

export async function patchTool(toolId: string, body: ToolPatchBody): Promise<ToolDefinition> {
  const res = await fetch(apiUrl(`/api/admin/tools/${encodeURIComponent(toolId)}`), {
    method: 'PATCH',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<ToolDefinition>(res)
}

export async function getReactDefaultToolSet(tenantId?: TenantId): Promise<ToolSetConfig> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/admin/tools/sets/react-default${qs}`), { headers: apiHeaders() })
  return parseApiResponse<ToolSetConfig>(res)
}

export async function putReactDefaultToolSet(
  toolIds: string[],
  tenantId?: TenantId,
): Promise<ToolSetConfig> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/admin/tools/sets/react-default${qs}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ toolIds }),
  })
  return parseApiResponse<ToolSetConfig>(res)
}

export async function getPlanWorkflowCriticalToolSet(tenantId?: TenantId): Promise<ToolSetConfig> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/admin/tools/sets/plan-workflow-critical${qs}`), { headers: apiHeaders() })
  return parseApiResponse<ToolSetConfig>(res)
}

export async function putPlanWorkflowCriticalToolSet(
  toolIds: string[],
  tenantId?: TenantId,
): Promise<ToolSetConfig> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/admin/tools/sets/plan-workflow-critical${qs}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ toolIds }),
  })
  return parseApiResponse<ToolSetConfig>(res)
}

export async function getPlanWorkflowModePolicy(tenantId?: TenantId): Promise<PlanWorkflowExecutionPolicy> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/admin/tools/modes/plan-workflow${qs}`), { headers: apiHeaders() })
  return parseApiResponse<PlanWorkflowExecutionPolicy>(res)
}

export async function putPlanWorkflowModePolicy(
  policy: PlanWorkflowExecutionPolicy,
  tenantId?: TenantId,
): Promise<PlanWorkflowExecutionPolicy> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/admin/tools/modes/plan-workflow${qs}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(policy),
  })
  return parseApiResponse<PlanWorkflowExecutionPolicy>(res)
}

export async function listToolCatalog(
  tenantId?: TenantId,
  enabledOnly = false,
): Promise<ToolCatalogEntry[]> {
  const params = new URLSearchParams()
  if (tenantId) params.set('tenantId', tenantId)
  if (enabledOnly) params.set('enabledOnly', 'true')
  const qs = params.toString()
  const res = await fetch(apiUrl(`/api/tools/catalog${qs ? `?${qs}` : ''}`), { headers: apiHeaders() })
  return parseApiResponse<ToolCatalogEntry[]>(res)
}

export function filterMcpTools(catalog: ToolCatalogEntry[], serverId: string): ToolCatalogEntry[] {
  const prefix = `mcp__${serverId}__`
  return catalog.filter(t => t.id.startsWith(prefix))
}

export async function loadToolEnabledMap(tenantId?: TenantId): Promise<Map<string, boolean>> {
  const [all, enabled] = await Promise.all([
    listToolCatalog(tenantId, false),
    listToolCatalog(tenantId, true),
  ])
  const enabledSet = new Set(enabled.map(t => t.id))
  const map = new Map<string, boolean>()
  for (const tool of all) {
    map.set(tool.id, enabledSet.has(tool.id))
  }
  return map
}
