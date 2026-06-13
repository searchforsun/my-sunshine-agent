const LIST_RE = /^(\s*)([-*+]|\d+[.)])\s+/

/** 行是否为列表项（- / * / + / 1. / 1) 开头） */
export function isListLine(line: string): boolean {
  return LIST_RE.test(line)
}

/** SSE 分块可能只收到不完整列表前缀，不应单独渲染为段落 */
export function isPartialListMarker(s: string): boolean {
  const t = s.trim()
  if (!t) return false
  if (/^[-*+]$/.test(t)) return true
  if (/^[-*+]\s$/.test(t)) return true
  if (/^\d+\.?\)?$/.test(t)) return true
  if (/^\d+[.)]\s*$/.test(t)) return true
  return false
}

/** 行是否缩进（列表项续行） */
export function isIndented(line: string): boolean {
  return /^\s{2,}\S/.test(line)
}
