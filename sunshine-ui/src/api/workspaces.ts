import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

export interface WorkspaceVO {
  id: string
  name: string
  repoUrl: string
  repoBranch: string
  sandboxProfile: string
  memoryMb: number
  cpus: number
  image: string
  status: string
  cloneState: string | null
  createdAt: string
}

export interface CreateWorkspaceRequest {
  name?: string
  repoUrl: string
  repoBranch?: string
  memoryMb?: number
  cpus?: number
}

export async function listWorkspaces(): Promise<WorkspaceVO[]> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces`, { headers: apiHeaders() })
  return parseApiResponse<WorkspaceVO[]>(res)
}

export interface WorkspaceListPage {
  items: WorkspaceVO[]
  hasMore: boolean
}

export async function listWorkspacesPage(opts?: {
  limit?: number
  offset?: number
}): Promise<WorkspaceListPage> {
  const params = new URLSearchParams()
  params.set('limit', String(opts?.limit ?? 30))
  params.set('offset', String(opts?.offset ?? 0))
  const res = await fetch(
    `${resolveApiBase()}/api/agent-workspaces?${params}`,
    { headers: apiHeaders() },
  )
  const raw = await parseApiResponse<{ items?: WorkspaceVO[]; hasMore?: boolean }>(res)
  return {
    items: Array.isArray(raw.items) ? raw.items : [],
    hasMore: raw.hasMore === true,
  }
}

export async function createWorkspace(req: CreateWorkspaceRequest): Promise<WorkspaceVO> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify(req),
  })
  return parseApiResponse<WorkspaceVO>(res)
}

export async function destroyWorkspace(id: string): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export interface WorkspaceSyncResult {
  action: 'cloned' | 'pulled'
  branch: string
  output?: string
}

export async function syncWorkspace(id: string): Promise<WorkspaceSyncResult> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(id)}/sync`, {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<WorkspaceSyncResult>(res)
}

export interface ProjectGuide {
  content: string
  updatedAt: string
}

export async function getProjectGuide(id: string): Promise<ProjectGuide> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(id)}/project-guide`, {
    headers: apiHeaders(),
  })
  return parseApiResponse<ProjectGuide>(res)
}

export async function saveProjectGuide(id: string, content: string): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(id)}/project-guide`, {
    method: 'PUT',
    headers: apiHeaders(),
    body: JSON.stringify({ content }),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}
