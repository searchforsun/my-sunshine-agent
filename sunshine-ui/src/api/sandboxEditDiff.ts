/** 沙箱 edit 展开：git 风格行级 unified diff（同屏 +/-） */

export type SandboxDiffLine = {
  kind: 'del' | 'add' | 'ctx'
  text: string
}

function splitLines(text: string): string[] {
  if (text === '') return ['']
  return text.split('\n')
}

/** 行级 LCS → unified（公共行 ctx，删除 -，新增 +） */
export function lineUnifiedDiff(oldText: string, newText: string): SandboxDiffLine[] {
  const a = splitLines(oldText ?? '')
  const b = splitLines(newText ?? '')
  const n = a.length
  const m = b.length
  const dp: number[][] = Array.from({ length: n + 1 }, () => Array(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j]
        ? dp[i + 1][j + 1] + 1
        : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: SandboxDiffLine[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ kind: 'ctx', text: a[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ kind: 'del', text: a[i++] })
    } else {
      out.push({ kind: 'add', text: b[j++] })
    }
  }
  while (i < n) out.push({ kind: 'del', text: a[i++] })
  while (j < m) out.push({ kind: 'add', text: b[j++] })
  return out
}

export function formatDiffLinesAsText(lines: SandboxDiffLine[]): string {
  return lines.map(l => {
    const p = l.kind === 'del' ? '-' : l.kind === 'add' ? '+' : ' '
    return `${p}${l.text}`
  }).join('\n')
}

export function summarizeDiffCounts(lines: SandboxDiffLine[]): { add: number; del: number } {
  let add = 0
  let del = 0
  for (const l of lines) {
    if (l.kind === 'add') add++
    else if (l.kind === 'del') del++
  }
  return { add, del }
}

/** 解析 expand detail：旧 <<< old / >>> new，或已是 +/- 文本 */
export function parseSandboxEditDiff(raw: string): SandboxDiffLine[] | null {
  if (!raw?.trim()) return null
  const trimmed = raw.replace(/^\uFEFF/, '')
  const oldMarker = trimmed.match(/^<<<\s*old\s*\n/i)
  if (oldMarker) {
    const rest = trimmed.slice(oldMarker[0].length)
    const split = rest.split(/\n>>> ?new\s*\n/i)
    const oldPart = (split[0] ?? '').replace(/\n$/, '')
    const newPart = split[1] ?? ''
    return lineUnifiedDiff(oldPart, newPart)
  }
  const lines = trimmed.split('\n')
  const prefixed = lines.filter(l => /^[+\- ]/.test(l))
  if (prefixed.length >= 1 && prefixed.length === lines.length) {
    return lines.map(line => {
      const mark = line[0]
      const text = line.slice(1)
      if (mark === '-') return { kind: 'del' as const, text }
      if (mark === '+') return { kind: 'add' as const, text }
      return { kind: 'ctx' as const, text }
    })
  }
  return null
}

/** write 展开：全文视为新增行（绿底 +N） */
export function writeContentAsAddLines(raw: string): SandboxDiffLine[] {
  if (raw == null || raw === '') return []
  const normalized = raw.endsWith('\n') ? raw.slice(0, -1) : raw
  if (normalized === '') return [{ kind: 'add', text: '' }]
  return normalized.split('\n').map(text => ({ kind: 'add' as const, text }))
}

export function isSandboxWriteStep(step: { id: string }): boolean {
  if (!step.id?.startsWith('tool-')) return false
  const toolId = step.id.slice('tool-'.length).split('@')[0]?.trim()
  return toolId === 'sandbox__write'
}

export function isSandboxEditStep(step: { id: string }): boolean {
  if (!step.id?.startsWith('tool-')) return false
  const toolId = step.id.slice('tool-'.length).split('@')[0]?.trim()
  return toolId === 'sandbox__edit'
}
