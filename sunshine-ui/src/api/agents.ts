import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface AgentCatalogIndexEntry {
  id: string
  displayName: string
  description: string
  enabled: boolean
  source?: string
  /** 会话形态：chat | task | all */
  kind?: string
}

export interface AgentEntry {
  id: string
  displayName: string
  description: string
  systemPrompt: string
  skillIds: string[]
  tags: string[]
  toolsJson: string
  enabled: boolean
  source: string
  tenantId?: string
  kbScope?: string[]
  dataScopeJson?: string
  permissionsJson?: string
  modelConfigJson?: string
  maxIters: number
  maxHandoffs: number
  agentCardUrl?: string
  authConfigJson?: string
  endpointOverride?: string
  /** 会话形态：chat | task | all */
  kind?: string
}

export async function listAgents(): Promise<AgentEntry[]> {
  const res = await fetch(apiUrl('/api/agents'), { headers: apiHeaders() })
  return parseApiResponse<AgentEntry[]>(res)
}

export async function listAgentCatalogIndex(): Promise<AgentCatalogIndexEntry[]> {
  const res = await fetch(apiUrl('/api/agents/catalog/index'), { headers: apiHeaders() })
  return parseApiResponse<AgentCatalogIndexEntry[]>(res)
}

export async function createAgent(
  id: string,
  displayName: string,
  systemPrompt: string,
  description?: string,
  skillIds?: string[],
  toolIds?: string[],
  source?: string,
  agentCardUrl?: string,
  authConfigJson?: string,
  endpointOverride?: string,
  extra?: Partial<AgentEntry>,
): Promise<AgentEntry> {
  const body: Record<string, unknown> = {
    id,
    displayName,
    systemPrompt,
    description: description ?? '',
    skillIds: skillIds ?? [],
    toolIds: toolIds ?? [],
    source: source ?? 'INTERNAL',
  }
  if (agentCardUrl) body.agentCardUrl = agentCardUrl
  if (authConfigJson) body.authConfig = extJsonToObj(authConfigJson)
  if (endpointOverride) body.endpointOverride = endpointOverride
  if (extra) Object.assign(body, extra)
  const res = await fetch(apiUrl('/api/agents'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<AgentEntry>(res)
}

export async function updateAgent(
  id: string,
  displayName: string,
  systemPrompt: string,
  description?: string,
  skillIds?: string[],
  toolIds?: string[],
  extra?: Partial<AgentEntry>,
): Promise<AgentEntry> {
  const body: Record<string, unknown> = {
    displayName,
    systemPrompt,
    description: description ?? '',
    skillIds: skillIds ?? [],
    toolIds: toolIds ?? [],
  }
  if (extra) Object.assign(body, extra)
  const res = await fetch(apiUrl(`/api/agents/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<AgentEntry>(res)
}

export async function setAgentEnabled(id: string, enabled: boolean): Promise<AgentEntry> {
  const res = await fetch(apiUrl(`/api/agents/${encodeURIComponent(id)}/enable`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  return parseApiResponse<AgentEntry>(res)
}

export async function deleteAgent(id: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/agents/${encodeURIComponent(id)}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}

export interface AgentCardPreFill {
  displayName: string
  description: string
  version: string
  skills: string[]
  endpointUrl: string
  error: string | null
}

export async function fetchAgentCard(agentCardUrl: string): Promise<AgentCardPreFill> {
  const params = new URLSearchParams({ agentCardUrl })
  const res = await fetch(apiUrl(`/api/agents/external/card-prefill?${params}`), { headers: apiHeaders() })
  return parseApiResponse<AgentCardPreFill>(res)
}

function extJsonToObj(json: string): unknown {
  try {
    return JSON.parse(json)
  } catch {
    return {}
  }
}
