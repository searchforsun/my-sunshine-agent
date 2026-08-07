import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'
import type { SandboxDiffLine } from './sandboxEditDiff'

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

/** 单区（已暂存/未暂存）行数统计 */
export interface GitDiffCounts {
  added: number
  deleted: number
}

/** 工作区改动文件摘要项 */
export interface GitDiffSummaryItem {
  path: string
  /** 归一化单字母状态（M/A/D/R/?），合并视图徽章用 */
  status: string
  /** porcelain XY 码（MM/ M/?? 等），区分已暂存/未暂存 */
  rawStatus: string
  /** 合并行数（HEAD vs 工作区），向后兼容 */
  added: number
  deleted: number
  binary: boolean
  /** 已暂存行数（HEAD vs 暂存区） */
  staged: GitDiffCounts
  /** 未暂存行数（暂存区 vs 工作区；未跟踪文件为全量新增） */
  unstaged: GitDiffCounts
}

/** 单文件 diff 的一区内容 */
export interface GitDiffPart {
  /** 该区是否存在改动 */
  present: boolean
  lines: SandboxDiffLine[]
}

/** 单文件 diff 详情：结构化 diff 行（与沙箱 editDiff 行同构） */
export interface GitDiffDetail {
  path: string
  /** 合并 diff（HEAD vs 工作区），向后兼容 */
  lines: SandboxDiffLine[]
  /** 已暂存 diff（HEAD vs 暂存区） */
  staged: GitDiffPart
  /** 未暂存 diff（暂存区 vs 工作区；未跟踪为全量新增） */
  unstaged: GitDiffPart
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

/** 回退指定文件改动到 HEAD（未跟踪文件删除） */
export async function gitRevert(workspaceId: string, checkoutId: string, files: string[]): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/revert?checkoutId=${encodeURIComponent(checkoutId)}`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ files }),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

/** 撤回暂存（仅清暂存区，保留工作区改动） */
export async function gitUnstage(workspaceId: string, checkoutId: string, files: string[]): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/unstage?checkoutId=${encodeURIComponent(checkoutId)}`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ files }),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

const DIFF_BASE_KEY = 'sunshine-diff-base'

/**
 * 每轮改动基线：发送消息瞬间工作区已有的改动文件集合。
 * 消息完成后的 diff 卡片用它做差集，只展示「本轮新增」的改动文件。
 */
export function saveDiffBaseSnapshot(conversationId: string, paths: string[]) {
  try {
    localStorage.setItem(`${DIFF_BASE_KEY}:${conversationId}`, JSON.stringify(paths))
  } catch { /* ignore */ }
}

export function loadDiffBaseSnapshot(conversationId: string): Set<string> {
  try {
    const raw = localStorage.getItem(`${DIFF_BASE_KEY}:${conversationId}`)
    if (!raw) return new Set()
    const arr = JSON.parse(raw) as unknown
    if (!Array.isArray(arr)) return new Set()
    return new Set(arr.filter((x): x is string => typeof x === 'string'))
  } catch {
    return new Set()
  }
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

/** 工作区改动文件摘要（HEAD + 未跟踪） */
export async function gitDiffSummary(workspaceId: string, checkoutId: string): Promise<GitDiffSummaryItem[]> {
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/diff?checkoutId=${encodeURIComponent(checkoutId)}`, {
    headers: apiHeaders(),
  })
  return parseApiResponse<GitDiffSummaryItem[]>(res)
}

/** 单文件 diff 详情（结构化 diff 行） */
export async function gitDiffFile(workspaceId: string, checkoutId: string, path: string): Promise<GitDiffDetail> {
  const q = new URLSearchParams({ checkoutId, path })
  const res = await fetch(`${resolveApiBase()}/api/agent-workspaces/${encodeURIComponent(workspaceId)}/git/diff?${q}`, {
    headers: apiHeaders(),
  })
  return parseApiResponse<GitDiffDetail>(res)
}
