import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { ApiError, parseBffPayload } from './apiError'

const API_BASE = () => resolveApiBase()

export interface SandboxFsNode {
  name: string
  path: string
  type: 'file' | 'dir' | string
  size?: number | null
}

export interface SandboxFsList {
  path: string
  entries: SandboxFsNode[]
}

export interface SandboxFsContent {
  path: string
  content: string
  truncated: boolean
  binary: boolean
}

export async function fetchSandboxWorkspaceStatus(conversationId: string): Promise<boolean> {
  const res = await fetch(
    `${API_BASE()}/api/conversations/${encodeURIComponent(conversationId)}/sandbox/workspace/status`,
    { headers: apiHeaders() },
  )
  if (res.status === 404) return false
  const data = await parseBffPayload<{ active?: boolean }>(res)
  return !!data?.active
}

export async function listSandboxWorkspace(
  conversationId: string,
  path = '/workspace',
): Promise<SandboxFsList> {
  const q = new URLSearchParams({ path })
  const res = await fetch(
    `${API_BASE()}/api/conversations/${encodeURIComponent(conversationId)}/sandbox/workspace?${q}`,
    { headers: apiHeaders() },
  )
  return parseBffPayload<SandboxFsList>(res)
}

export async function readSandboxWorkspaceFile(
  conversationId: string,
  path: string,
): Promise<SandboxFsContent> {
  const q = new URLSearchParams({ path })
  const res = await fetch(
    `${API_BASE()}/api/conversations/${encodeURIComponent(conversationId)}/sandbox/workspace/content?${q}`,
    { headers: apiHeaders() },
  )
  return parseBffPayload<SandboxFsContent>(res)
}
