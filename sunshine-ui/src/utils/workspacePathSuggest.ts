import type { SandboxFsNode } from '../api/sandboxWorkspace'
import { sandboxPathBasename } from './sandboxPathChip'

export interface WorkspacePathSuggestEntry {
  path: string
  name: string
  isDir: boolean
}

export const ROOTS = ['/workspace', '/skills'] as const
export type WorkspacePathRoot = (typeof ROOTS)[number]

const MAX_RESULTS = 12

function toEntries(nodes: SandboxFsNode[]): WorkspacePathSuggestEntry[] {
  return nodes.map((n) => ({ path: n.path, name: n.name, isDir: n.type === 'dir' }))
}

function sortEntries(entries: WorkspacePathSuggestEntry[]): WorkspacePathSuggestEntry[] {
  return [...entries].sort((a, b) => {
    const ad = a.isDir ? 0 : 1
    const bd = b.isDir ? 0 : 1
    if (ad !== bd) return ad - bd
    return a.name.localeCompare(b.name)
  })
}

/**
 * 懒加载工作区路径索引：默认只加载根目录第一层；输入 query 后按路径段
 * 逐级懒加载匹配的目录分支，避免递归全量拉取整个工作区。
 * 目录列举通过注入的 listDir 完成（复用文件树同款 `listSandboxWorkspace` 按目录加载）。
 */
export class WorkspacePathSuggestIndex {
  private childrenCache = new Map<string, WorkspacePathSuggestEntry[]>()
  private inflight = new Map<string, Promise<WorkspacePathSuggestEntry[]>>()

  constructor(private listDir: (dirPath: string) => Promise<SandboxFsNode[]>) {}

  /** 加载某目录子项（带缓存与并发去重） */
  async ensureDir(dirPath: string): Promise<WorkspacePathSuggestEntry[]> {
    const cached = this.childrenCache.get(dirPath)
    if (cached) return cached
    const running = this.inflight.get(dirPath)
    if (running) return running
    const task = this.listDir(dirPath)
      .then((nodes) => sortEntries(toEntries(nodes)))
      .catch(() => [] as WorkspacePathSuggestEntry[])
      .then((entries) => {
        this.childrenCache.set(dirPath, entries)
        return entries
      })
      .finally(() => this.inflight.delete(dirPath))
    this.inflight.set(dirPath, task)
    return task
  }

  /** 各根目录第一层合并（空 query 或仅斜杠时展示） */
  private async topLevel(roots: readonly WorkspacePathRoot[]): Promise<WorkspacePathSuggestEntry[]> {
    const lists = await Promise.all(roots.map((r) => this.ensureDir(r)))
    return lists.flat()
  }

  /**
   * 第一层 + 每个第一层目录的第二层子项，合并后整体排序。
   * 空 query 默认展示两层，避免工作区模式下只露出 wt-xxx 根目录本身。
   */
  private async topTwoLevels(roots: readonly WorkspacePathRoot[]): Promise<WorkspacePathSuggestEntry[]> {
    const firstLevel = await this.topLevel(roots)
    const out: WorkspacePathSuggestEntry[] = []
    for (const e of firstLevel) {
      // 第一层目录保持在其子项之前，维持层级直觉
      out.push(e)
      if (e.isDir) {
        const kids = await this.ensureDir(e.path)
        out.push(...sortEntries(kids))
      }
    }
    return out
  }

  /** 按目录段逐级定位并加载父目录（如 ['src','deep'] → 逐级加载 /workspace/src/deep） */
  private async resolveDir(
    dirSegments: string[],
    roots: readonly WorkspacePathRoot[],
  ): Promise<WorkspacePathSuggestEntry[] | null> {
    for (const root of roots) {
      let dirPath: string = root
      let entries = await this.ensureDir(dirPath)
      let ok = true
      for (const seg of dirSegments) {
        const child = entries.find((e) => e.isDir && e.name === seg)
        if (!child) {
          ok = false
          break
        }
        dirPath = child.path
        entries = await this.ensureDir(dirPath)
      }
      if (ok) return entries
    }
    return null
  }

  /**
   * 懒加载搜索：
   * - 空 query → 加载第一层 + 各目录第二层（默认两层）
   * - 非空 → 只加载 query 匹配的目录分支（父目录逐级按需加载）
   * - `src/`（尾部斜杠）→ 展示 src 目录全部子项
   * - `workspace/...` / `skills/...` 前缀显式限定根
   */
  async search(
    query: string,
    roots: readonly WorkspacePathRoot[] = ROOTS,
    limit = MAX_RESULTS,
  ): Promise<WorkspacePathSuggestEntry[]> {
    const q = query.trim()
    if (!q) return (await this.topTwoLevels(roots)).slice(0, limit)

    const clean = q.startsWith('/') ? q.slice(1) : q
    const trailing = clean.endsWith('/')
    const segments = clean.split('/').filter(Boolean)
    if (segments.length === 0) return (await this.topTwoLevels(roots)).slice(0, limit)

    let effectiveRoots = roots
    let dirSegments = trailing ? segments : segments.slice(0, -1)
    if (dirSegments[0] === 'workspace' || dirSegments[0] === 'skills') {
      effectiveRoots = [dirSegments[0] === 'skills' ? '/skills' : '/workspace']
      dirSegments = dirSegments.slice(1)
    }

    const last = trailing ? '' : segments[segments.length - 1].toLowerCase()
    const parentEntries = await this.resolveDir(dirSegments, effectiveRoots)
    const source = parentEntries ?? (await this.topLevel(effectiveRoots))
    return last ? filterWorkspacePaths(source, last, limit) : source.slice(0, limit)
  }

  /** 失效缓存（工作区刷新 / 切换会话时调用） */
  invalidate(): void {
    this.childrenCache.clear()
    this.inflight.clear()
  }
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

/** 判断路径是否为目录：存在以 `p/` 开头的索引项即为目录，否则视为文件 */
function isIndexDir(path: string, index: Set<string>): boolean {
  const prefix = path.endsWith('/') ? path : `${path}/`
  for (const p of index) {
    if (p.startsWith(prefix)) return true
  }
  return false
}

/**
 * 全量索引搜索：给定扁平路径数组（复用 `window.__smd_sandboxIndex` 或后端索引接口），
 * 按 name / path 模糊匹配任意层级的文件与目录。
 * 目录判断优先用「存在子路径」精确判定，避免无扩展名文件被误判。
 */
export function searchFlatPaths(
  paths: string[],
  query: string,
  limit = MAX_RESULTS,
): WorkspacePathSuggestEntry[] {
  const q = query.trim().toLowerCase()
  if (!q) return []
  const index = new Set(paths)
  const matched: WorkspacePathSuggestEntry[] = []
  for (const path of paths) {
    const name = sandboxPathBasename(path)
    const pathLower = path.toLowerCase()
    if (!pathLower.includes(q) && !name.toLowerCase().includes(q)) continue
    matched.push({ path, name, isDir: isIndexDir(path, index) })
  }
  return sortEntries(matched).slice(0, limit)
}

/** 输入框尾部 `@query` 触发工作区路径补全（符号与 Skill 互换：原 `/` 触发改为 `@`） */
export function matchWorkspacePathMention(text: string): { start: number; query: string } | null {
  const match = text.match(/@([\w\u4e00-\u9fff./_-]*)$/)
  if (!match || match.index == null) return null
  return { start: match.index, query: match[1] ?? '' }
}
