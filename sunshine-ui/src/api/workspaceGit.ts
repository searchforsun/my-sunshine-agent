import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

export interface CheckoutInfo {
  checkoutId: string
  branch: string
  path: string
  conversationIds: string[]
}

export interface GitBranchInfo {
  name: string
  type: 'local' | 'remote'
  current: boolean
}

export interface GitStatus {
  branch: string
  files: string
}

export async function listCheckouts(workspaceId: string): Promise<CheckoutInfo[]> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/checkouts`, {
    headers: apiHeaders(),
  })
  return parseApiResponse<CheckoutInfo[]>(res)
}

/** 列出本地 + 远程分支 */
export async function listBranches(workspaceId: string): Promise<GitBranchInfo[]> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/branches`, {
    headers: apiHeaders(),
  })
  return parseApiResponse<GitBranchInfo[]>(res)
}

/** 新建 worktree checkout（懒创建）；返回新 checkoutId */
export async function createCheckout(workspaceId: string, branch: string, from?: string): Promise<string> {
  const body: Record<string, string> = { branch }
  if (from) body.from = from
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/checkouts`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify(body),
  })
  return parseApiResponse<string>(res)
}

/** 按分支名幂等确保 checkout 存在（已有复用、无则懒创建）；返回 checkoutId */
export async function ensureCheckout(workspaceId: string, branch: string): Promise<string> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/checkouts/ensure`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ branch }),
  })
  return parseApiResponse<string>(res)
}

export async function removeCheckout(workspaceId: string, checkoutId: string): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/checkouts/${encodeURIComponent(checkoutId)}`, {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function gitStatus(workspaceId: string, checkoutId: string): Promise<GitStatus> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/status?checkoutId=${encodeURIComponent(checkoutId)}`, {
    headers: apiHeaders(),
  })
  return parseApiResponse<GitStatus>(res)
}

export async function gitStage(workspaceId: string, checkoutId: string, files?: string[], all?: boolean): Promise<void> {
  const body: Record<string, unknown> = {}
  if (all) body.all = true
  else if (files) body.files = files
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/stage?checkoutId=${encodeURIComponent(checkoutId)}`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify(body),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function gitCommit(workspaceId: string, checkoutId: string, message: string): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/commit?checkoutId=${encodeURIComponent(checkoutId)}`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ message }),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function gitPush(workspaceId: string, checkoutId: string): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/push?checkoutId=${encodeURIComponent(checkoutId)}`, {
    method: 'POST',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function gitPull(workspaceId: string, checkoutId: string): Promise<{ action?: string; branch?: string; output?: string }> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/pull?checkoutId=${encodeURIComponent(checkoutId)}`, {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<{ action?: string; branch?: string; output?: string }>(res)
}
