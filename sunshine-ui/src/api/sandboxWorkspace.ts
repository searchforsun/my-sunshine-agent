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
  offset: number
  totalSize: number
}

export async function fetchSandboxWorkspaceStatus(conversationId: string): Promise<boolean> {
  const ctrl = new AbortController()
  const timeoutId = setTimeout(() => ctrl.abort(), 8000)
  try {
    const res = await fetch(
      `${API_BASE()}/api/conversations/${encodeURIComponent(conversationId)}/sandbox/workspace/status`,
      { headers: apiHeaders(), signal: ctrl.signal },
    )
    if (res.status === 404) return false
    const data = await parseBffPayload<{ active?: boolean }>(res)
    return !!data?.active
  } catch {
    return false
  } finally {
    clearTimeout(timeoutId)
  }
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
  offset = 0,
): Promise<SandboxFsContent> {
  const q = new URLSearchParams({ path, offset: String(offset) })
  const res = await fetch(
    `${API_BASE()}/api/conversations/${encodeURIComponent(conversationId)}/sandbox/workspace/content?${q}`,
    { headers: apiHeaders() },
  )
  return parseBffPayload<SandboxFsContent>(res)
}

// ===== 工作区级别文件浏览（无需 conversationId） =====

export async function listWorkspaceSandboxFiles(
  workspaceId: string,
  path = '/workspace',
): Promise<SandboxFsList> {
  const q = new URLSearchParams({ path })
  const res = await fetch(
    `${API_BASE()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/sandbox/workspace?${q}`,
    { headers: apiHeaders() },
  )
  return parseBffPayload<SandboxFsList>(res)
}

export async function readWorkspaceSandboxFile(
  workspaceId: string,
  path: string,
  offset = 0,
): Promise<SandboxFsContent> {
  const q = new URLSearchParams({ path, offset: String(offset) })
  const res = await fetch(
    `${API_BASE()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/sandbox/workspace/content?${q}`,
    { headers: apiHeaders() },
  )
  return parseBffPayload<SandboxFsContent>(res)
}

/** 文件索引（递归扁平化路径列表） */
export async function fetchSandboxFileIndex(
  conversationId: string,
  path = '/workspace',
  maxDepth = 64,
): Promise<string[]> {
  const q = new URLSearchParams({ path, maxDepth: String(maxDepth) })
  const res = await fetch(
    `${API_BASE()}/api/conversations/${encodeURIComponent(conversationId)}/sandbox/workspace/index?${q}`,
    { headers: apiHeaders() },
  )
  const data = await parseBffPayload<{ root: string; paths: string[] }>(res)
  return data?.paths ?? []
}

/** 工作区级别文件索引（无需 conversationId） */
export async function fetchWorkspaceFileIndex(
  workspaceId: string,
  path = '/workspace',
  maxDepth = 64,
): Promise<string[]> {
  const q = new URLSearchParams({ path, maxDepth: String(maxDepth) })
  const res = await fetch(
    `${API_BASE()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/sandbox/workspace/index?${q}`,
    { headers: apiHeaders() },
  )
  const data = await parseBffPayload<{ root: string; paths: string[] }>(res)
  return data?.paths ?? []
}
