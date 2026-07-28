import { listSandboxWorkspace, type SandboxFsNode } from '../api/sandboxWorkspace'
import { sandboxPathBasename } from './sandboxPathChip'

export interface WorkspacePathSuggestEntry {
  path: string
  name: string
  isDir: boolean
}

const ROOTS = ['/workspace', '/skills'] as const
export type WorkspacePathRoot = (typeof ROOTS)[number]
const MAX_DEPTH = 10
const MAX_ENTRIES = 500

function sortNodes(entries: SandboxFsNode[]): SandboxFsNode[] {
  return [...entries].sort((a, b) => {
    const ad = a.type === 'dir' ? 0 : 1
    const bd = b.type === 'dir' ? 0 : 1
    if (ad !== bd) return ad - bd
    return a.name.localeCompare(b.name)
  })
}

async function walkDir(
  conversationId: string,
  dirPath: string,
  acc: WorkspacePathSuggestEntry[],
  depth: number,
): Promise<void> {
  if (depth > MAX_DEPTH || acc.length >= MAX_ENTRIES) return
  let data
  try {
    data = await listSandboxWorkspace(conversationId, dirPath)
  } catch {
    return
  }
  for (const node of sortNodes(data.entries ?? [])) {
    if (acc.length >= MAX_ENTRIES) return
    const isDir = node.type === 'dir'
    acc.push({
      path: node.path,
      name: node.name,
      isDir,
    })
    if (isDir) {
      await walkDir(conversationId, node.path, acc, depth + 1)
    }
  }
}

/** 递归拉取工作区路径（可按根目录分片缓存） */
export async function collectWorkspacePaths(
  conversationId: string,
  roots: readonly WorkspacePathRoot[] = ROOTS,
): Promise<WorkspacePathSuggestEntry[]> {
  const acc: WorkspacePathSuggestEntry[] = []
  for (const root of roots) {
    if (acc.length >= MAX_ENTRIES) break
    acc.push({ path: root, name: sandboxPathBasename(root), isDir: true })
    await walkDir(conversationId, root, acc, 1)
  }
  return acc
}

export function filterWorkspacePaths(
  entries: WorkspacePathSuggestEntry[],
  query: string,
  limit = 12,
): WorkspacePathSuggestEntry[] {
  const q = query.trim().toLowerCase()
  if (!q) return entries.slice(0, limit)
  const normalized = q.startsWith('/') ? q : `/${q}`
  return entries
    .filter((e) => {
      const pathLower = e.path.toLowerCase()
      const nameLower = e.name.toLowerCase()
      return pathLower.includes(normalized)
        || pathLower.includes(q)
        || nameLower.includes(q)
    })
    .slice(0, limit)
}

/** 输入框尾部 `@query` 触发工作区路径补全（符号与 Skill 互换：原 `/` 触发改为 `@`） */
export function matchWorkspacePathMention(text: string): { start: number; query: string } | null {
  const match = text.match(/@([\w\u4e00-\u9fff./_-]*)$/)
  if (!match || match.index == null) return null
  return { start: match.index, query: match[1] ?? '' }
}
