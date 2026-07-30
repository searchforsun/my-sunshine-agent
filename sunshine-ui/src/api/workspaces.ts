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
  name: string
  repoUrl: string
  repoBranch?: string
  memoryMb?: number
  cpus?: number
}

export async function listWorkspaces(): Promise<WorkspaceVO[]> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces`, { headers: apiHeaders() })
  return parseApiResponse<WorkspaceVO[]>(res)
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
