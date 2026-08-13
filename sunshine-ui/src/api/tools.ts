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
  source: string
  sourceRef: string
  timelineSummaryTemplate?: string
  timelineSummaryExtract?: string | null
  parameters: Record<string, unknown>
  sideEffect: string
  requireConfirmation: boolean
  enabled: boolean
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
  timelineSummaryTemplate?: string
  timelineSummaryExtract?: string | null
  sideEffect: string
  tenantId: string
  enabled: boolean
  metadataEdited: boolean
  discoveredAt: string | null
  updatedAt: string
}

export interface ToolPatchBody {
  enabled?: boolean
  displayName?: string
  description?: string
  requireConfirmation?: boolean
  timelineSummaryTemplate?: string
  timelineSummaryExtract?: string | null
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

/** 按来源过滤 Catalog 工具（SSOT：source + sourceRef，非 id 前缀） */
export function filterCatalogBySource(
  catalog: ToolCatalogEntry[],
  source: string,
  sourceRef: string,
): ToolCatalogEntry[] {
  return catalog.filter(t => t.source === source && t.sourceRef === sourceRef)
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

export type ToolSetKindPath = 'chat' | 'task'

export interface ToolSetMemberItem {
  toolId: string
  displayName: string
  description: string
  source: string
  sourceRef: string
  sourceLabel: string
  sideEffect: string
  critical: boolean
  sortOrder: number
}

export interface ToolSetMembersPage {
  page: number
  size: number
  total: number
  items: ToolSetMemberItem[]
}

export interface ToolSetPickerTool {
  toolId: string
  displayName: string
  sideEffect: string
}

export interface ToolSetPickerGroup {
  source: string
  sourceRef: string
  title: string
  tools: ToolSetPickerTool[]
}

export interface ToolSetPickerResponse {
  groups: ToolSetPickerGroup[]
}

export interface ToolSetMemberAddItem {
  toolId: string
  critical?: boolean
}

export interface ToolSetMemberAddResult {
  added: string[]
  skipped: string[]
  rejected: { toolId: string; reason: string }[]
}

function toolSetTenantQs(tenantId?: TenantId): string {
  return tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
}

export async function pageToolSetMembers(
  kind: ToolSetKindPath,
  tenantId?: TenantId,
  page = 1,
  size = 20,
  q?: string,
): Promise<ToolSetMembersPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (tenantId) params.set('tenantId', tenantId)
  if (q?.trim()) params.set('q', q.trim())
  const res = await fetch(apiUrl(`/api/admin/tools/sets/${kind}/members?${params}`), { headers: apiHeaders() })
  return parseApiResponse<ToolSetMembersPage>(res)
}

export async function fetchToolSetPicker(
  kind: ToolSetKindPath,
  tenantId?: TenantId,
  q?: string,
): Promise<ToolSetPickerResponse> {
  const params = new URLSearchParams()
  if (tenantId) params.set('tenantId', tenantId)
  if (q?.trim()) params.set('q', q.trim())
  const qs = params.toString()
  const res = await fetch(apiUrl(`/api/admin/tools/sets/${kind}/picker${qs ? `?${qs}` : ''}`), { headers: apiHeaders() })
  return parseApiResponse<ToolSetPickerResponse>(res)
}

export async function addToolSetMembers(
  kind: ToolSetKindPath,
  items: ToolSetMemberAddItem[],
  tenantId?: TenantId,
): Promise<ToolSetMemberAddResult> {
  const res = await fetch(apiUrl(`/api/admin/tools/sets/${kind}/members:add${toolSetTenantQs(tenantId)}`), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ items }),
  })
  return parseApiResponse<ToolSetMemberAddResult>(res)
}

export async function removeToolSetMembers(
  kind: ToolSetKindPath,
  toolIds: string[],
  tenantId?: TenantId,
): Promise<void> {
  const res = await fetch(apiUrl(`/api/admin/tools/sets/${kind}/members:remove${toolSetTenantQs(tenantId)}`), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ toolIds }),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}

/** critical 仅对 task 默认集有意义 */
export async function patchTaskMemberCritical(
  toolId: string,
  critical: boolean,
  tenantId?: TenantId,
): Promise<void> {
  const res = await fetch(
    apiUrl(`/api/admin/tools/sets/task/members/${encodeURIComponent(toolId)}${toolSetTenantQs(tenantId)}`),
    {
      method: 'PATCH',
      headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ critical }),
    },
  )
  await parseApiResponse<void>(res, { allowEmptyData: true })
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

export function buildToolEnabledMap(catalog: ToolCatalogEntry[]): Map<string, boolean> {
  return new Map(catalog.map(t => [t.id, t.enabled]))
}
