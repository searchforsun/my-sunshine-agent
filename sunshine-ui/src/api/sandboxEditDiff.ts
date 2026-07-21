/** 沙箱 write/edit 展开：结构化 editDiff metadata + write 全文 add 行 */

export type SandboxDiffLineKind = 'del' | 'add' | 'ctx' | 'fold'

export type SandboxDiffLine = {
  kind: SandboxDiffLineKind
  text: string
  oldLine?: number | null
  newLine?: number | null
}

export type SandboxEditDiffMeta = {
  path?: string
  contextRadius?: number
  lines: SandboxDiffLine[]
}

function parseDiffLineKind(raw: unknown): SandboxDiffLineKind | null {
  if (raw === 'del' || raw === 'add' || raw === 'ctx' || raw === 'fold') return raw
  return null
}

function parseDiffLine(raw: unknown): SandboxDiffLine | null {
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const kind = parseDiffLineKind(o.kind)
  if (!kind) return null
  const text = typeof o.text === 'string' ? o.text : ''
  const oldLine = typeof o.oldLine === 'number' ? o.oldLine : o.oldLine === null ? null : undefined
  const newLine = typeof o.newLine === 'number' ? o.newLine : o.newLine === null ? null : undefined
  return { kind, text, oldLine, newLine }
}

/** SSE metadata.editDiff → 渲染行（无有效 lines 时 null） */
export function linesFromEditDiffMeta(meta?: SandboxEditDiffMeta | null): SandboxDiffLine[] | null {
  if (!meta || !Array.isArray(meta.lines) || meta.lines.length === 0) return null
  const lines = meta.lines
    .map(line => parseDiffLine(line))
    .filter((line): line is SandboxDiffLine => line != null)
  return lines.length > 0 ? lines : null
}

export function formatDiffLinesAsText(lines: SandboxDiffLine[]): string {
  return lines
    .filter(l => l.kind !== 'fold')
    .map(l => {
      const p = l.kind === 'del' ? '-' : l.kind === 'add' ? '+' : ' '
      return `${p}${l.text}`
    })
    .join('\n')
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

/** write 展开：全文视为新增行（绿底 + 新侧行号 1..N） */
export function writeContentAsAddLines(raw: string): SandboxDiffLine[] {
  if (raw == null || raw === '') return []
  const normalized = raw.endsWith('\n') ? raw.slice(0, -1) : raw
  if (normalized === '') return [{ kind: 'add', text: '', oldLine: null, newLine: 1 }]
  return normalized.split('\n').map((text, i) => ({
    kind: 'add' as const,
    text,
    oldLine: null,
    newLine: i + 1,
  }))
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
