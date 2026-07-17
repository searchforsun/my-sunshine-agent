/** 沙箱路径 Chip：拖入 Chat 胶囊高亮；plain 为反引号完整路径 */

export const SANDBOX_PATH_MIME = 'application/x-sunshine-sandbox-path'

export interface SandboxPathDragPayload {
  path: string
  name: string
  isDir: boolean
}

const PATH_TOKEN_RE = /`(\/(?:workspace|skills)(?:\/[^`\n]*)?)`/g

export function isSandboxContainerPath(path: string): boolean {
  return /^\/(?:workspace|skills)(?:\/|$)/.test(path.trim())
}

export function sandboxPathBasename(path: string): string {
  const normalized = path.replace(/\\/g, '/').replace(/\/+$/, '')
  if (normalized === '/workspace' || normalized === '/skills') {
    return normalized.slice(1)
  }
  const i = normalized.lastIndexOf('/')
  return i >= 0 ? normalized.slice(i + 1) : normalized
}

/** 发送给模型的纯文本 token */
export function sandboxPathPlainToken(path: string): string {
  return `\`${path.trim()}\``
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

/** 在 plain 文本中收集反引号沙箱路径 */
export function collectSandboxPathMatches(content: string): { index: number; end: number; path: string }[] {
  const matches: { index: number; end: number; path: string }[] = []
  PATH_TOKEN_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = PATH_TOKEN_RE.exec(content)) !== null) {
    matches.push({ index: m.index, end: m.index + m[0].length, path: m[1] })
  }
  return matches
}
