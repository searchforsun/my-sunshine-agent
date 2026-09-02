/** 文末是否存在未闭合的 ```mermaid 围栏 */
export function hasOpenMermaidFenceAtEnd(source: string): boolean {
  let open = false
  for (const line of source.split('\n')) {
    if (/^\s*`{3,}\s*mermaid\b/i.test(line)) {
      open = true
      continue
    }
    if (open && /^\s*`{3,}\s*$/.test(line)) {
      open = false
    }
  }
  return open
}

/** 流式渲染时裁掉文末未闭合 mermaid，避免 v-html 反复重建转圈 */
export function stripTrailingOpenMermaidFence(source: string): string {
  if (!hasOpenMermaidFenceAtEnd(source)) return source
  const lines = source.split('\n')
  for (let i = lines.length - 1; i >= 0; i--) {
    if (/^\s*`{3,}\s*mermaid\b/i.test(lines[i])) {
      return lines.slice(0, i).join('\n').replace(/\n+$/, '')
    }
  }
  return source
}
