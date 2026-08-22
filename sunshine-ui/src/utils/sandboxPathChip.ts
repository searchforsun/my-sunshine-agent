/** 沙箱路径 Chip：拖入 Chat 胶囊高亮；plain 为反引号完整路径 */

export const SANDBOX_PATH_MIME = 'application/x-sunshine-sandbox-path'

export interface SandboxPathDragPayload {
  path: string
  name: string
  isDir: boolean
}

const PATH_TOKEN_RE = /`(\/(?:workspace|skills)(?:\/[^`\n]*)?)`(?:\s+L(\d+)(?:-(\d+))?)?/g

export interface SandboxPathMatch {
  index: number
  end: number
  path: string
  lineStart?: number
  lineEnd?: number
}

export function isSandboxContainerPath(path: string): boolean {
  return /^\/(?:workspace|skills)(?:\/|$)/.test(path.trim())
}

/**
 * 基于后端文件索引精确匹配路径（非启发式）。
 * window.__smd_sandboxIndex 由会话级路径索引（进入会话即加载）填充（真实文件路径集合）。
 * - 绝对路径（/workspace/... /skills/...）：直接查集合；索引未就绪时仍信任（确定路径）
 * - 相对路径：结合 workspaceRoot 拼接后查集合；索引未就绪时不识别（避免误识别）
 */
export function matchSandboxPathByIndex(
  rawPath: string,
  workspaceRoot: string,
): { resolved: string; hit: boolean } {
  const t = rawPath.trim().replace(/\/+$/, '')
  if (!t) return { resolved: '', hit: false }
  const root = workspaceRoot.trim().replace(/\/+$/, '')
  const idx = (window as any).__smd_sandboxIndex as Set<string> | undefined
  // 绝对路径
  if (isSandboxContainerPath(t)) {
    if (idx && idx.size > 0) {
      // 索引就绪：精确匹配（含目录前缀匹配，支持目录路径点击）
      if (idx.has(t)) return { resolved: t, hit: true }
      for (const p of idx) {
        if (p.startsWith(t + '/')) return { resolved: t, hit: true }
      }
      return { resolved: '', hit: false }
    }
    // 索引未就绪：绝对路径是确定的，仍允许点击
    return { resolved: t, hit: false }
  }
  // 相对路径：必须有 root 才能解析
  if (!root || !isSandboxContainerPath(root)) return { resolved: '', hit: false }
  const resolved = `${root}/${t}`
  if (idx && idx.size > 0) {
    if (idx.has(resolved)) return { resolved, hit: true }
    // 目录前缀匹配
    for (const p of idx) {
      if (p.startsWith(resolved + '/')) return { resolved, hit: true }
    }
    // 单文件名 basename 后缀匹配：模型常只输出文件名（如 assemble-index.mjs），
    // 实际位于子目录（scripts/assemble-index.mjs）。仅对单段（无 /）启用，
    // 多段路径歧义大不启用。唯一命中即解析，多命中不解析（避免误识别）。
    if (!t.includes('/')) {
      const suffix = '/' + t
      let hitPath = ''
      let hitCount = 0
      for (const p of idx) {
        if (p.endsWith(suffix) && p.length > suffix.length) {
          hitPath = p
          hitCount++
          if (hitCount > 1) break
        }
      }
      if (hitCount === 1) return { resolved: hitPath, hit: true }
    }
    // 索引就绪但未命中 -> 不识别（精确匹配，避免 CSS/JS 误识别）
    return { resolved: '', hit: false }
  }
  // 索引未就绪：相对路径不识别（避免误识别）
  return { resolved: '', hit: false }
}

export function sandboxPathBasename(path: string): string {
  const normalized = path.replace(/\\/g, '/').replace(/\/+$/, '')
  if (normalized === '/workspace' || normalized === '/skills') {
    return normalized.slice(1)
  }
  const i = normalized.lastIndexOf('/')
  return i >= 0 ? normalized.slice(i + 1) : normalized
}

/** 发送给模型的纯文本 token；带行范围时输出 `path` L120-125 / `path` L120 */
export function sandboxPathPlainToken(
  path: string,
  lineStart?: number,
  lineEnd?: number,
): string {
  const base = `\`${path.trim()}\``
  if (typeof lineStart === 'number' && lineStart > 0) {
    const start = Math.floor(lineStart)
    const end = typeof lineEnd === 'number' && lineEnd >= start ? Math.floor(lineEnd) : start
    return `${base} L${start}${end > start ? `-${end}` : ''}`
  }
  return base
}

/** 启发式：无扩展名视为目录（与拖拽 isDir 一致，供 Chip 图标） */
export function isLikelySandboxDir(path: string): boolean {
  const normalized = path.replace(/\\/g, '/').replace(/\/+$/, '').trim()
  if (!normalized) return false
  if (normalized === '/workspace' || normalized === '/skills') return true
  const base = sandboxPathBasename(normalized)
  return !/\.[A-Za-z0-9]{1,12}$/.test(base)
}

export function parseSandboxPathDrag(dataTransfer: DataTransfer | null): SandboxPathDragPayload | null {
  if (!dataTransfer) return null
  const raw = dataTransfer.getData(SANDBOX_PATH_MIME)
  if (raw) {
    try {
      const o = JSON.parse(raw) as SandboxPathDragPayload
      if (o?.path && isSandboxContainerPath(o.path)) {
        return {
          path: o.path.trim(),
          name: o.name?.trim() || sandboxPathBasename(o.path),
          isDir: !!o.isDir,
        }
      }
    } catch { /* ignore */ }
  }
  const plain = dataTransfer.getData('text/plain')?.trim()
  if (plain && isSandboxContainerPath(plain)) {
    return {
      path: plain,
      name: sandboxPathBasename(plain),
      isDir: !/\.[^./]+$/.test(plain),
    }
  }
  const tick = plain?.match(/^`(\/(?:workspace|skills)(?:\/[^`]*)?)`$/)
  if (tick?.[1]) {
    return {
      path: tick[1],
      name: sandboxPathBasename(tick[1]),
      isDir: !/\.[^./]+$/.test(tick[1]),
    }
  }
  return null
}

export function setSandboxPathDrag(
  dataTransfer: DataTransfer,
  payload: SandboxPathDragPayload,
): void {
  dataTransfer.setData(SANDBOX_PATH_MIME, JSON.stringify(payload))
  dataTransfer.setData('text/plain', sandboxPathPlainToken(payload.path))
  dataTransfer.effectAllowed = 'copy'
}

/** 在 plain 文本中收集反引号沙箱路径（可选带行范围 `path` L120-125） */
export function collectSandboxPathMatches(content: string): SandboxPathMatch[] {
  const matches: SandboxPathMatch[] = []
  PATH_TOKEN_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = PATH_TOKEN_RE.exec(content)) !== null) {
    const lineStart = m[2] ? Number(m[2]) : undefined
    const lineEnd = m[3] ? Number(m[3]) : undefined
    matches.push({
      index: m.index,
      end: m.index + m[0].length,
      path: m[1],
      lineStart: lineStart && lineStart > 0 ? lineStart : undefined,
      lineEnd: lineEnd && lineEnd >= (lineStart ?? 0) ? lineEnd : undefined,
    })
  }
  return matches
}
