import type { SkillDiffLine } from '../api/skills'

export type SkillDiffViewMode = 'inline' | 'split'

export interface SplitDiffCell {
  lineNo: number | null
  text: string
  type: 'unchanged' | 'added' | 'removed' | 'empty'
}

export interface SplitDiffRow {
  left: SplitDiffCell
  right: SplitDiffCell
}

/** 将 unified diff 行转为左右对比行 */
export function toSplitDiffRows(lines: SkillDiffLine[]): SplitDiffRow[] {
  const rows: SplitDiffRow[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (line.type === 'unchanged') {
      rows.push({
        left: { lineNo: line.oldLineNo, text: line.text, type: 'unchanged' },
        right: { lineNo: line.newLineNo, text: line.text, type: 'unchanged' },
      })
      i += 1
      continue
    }
    if (line.type === 'removed') {
      const removed: SkillDiffLine[] = []
      while (i < lines.length && lines[i].type === 'removed') {
        removed.push(lines[i])
        i += 1
      }
      const added: SkillDiffLine[] = []
      while (i < lines.length && lines[i].type === 'added') {
        added.push(lines[i])
        i += 1
      }
      const max = Math.max(removed.length, added.length, 1)
      for (let k = 0; k < max; k += 1) {
        const r = removed[k]
        const a = added[k]
        rows.push({
          left: r
            ? { lineNo: r.oldLineNo, text: r.text, type: 'removed' }
            : { lineNo: null, text: '', type: 'empty' },
          right: a
            ? { lineNo: a.newLineNo, text: a.text, type: 'added' }
            : { lineNo: null, text: '', type: 'empty' },
        })
      }
      continue
    }
    rows.push({
      left: { lineNo: null, text: '', type: 'empty' },
      right: { lineNo: line.newLineNo, text: line.text, type: 'added' },
    })
    i += 1
  }
  return rows
}

export function inlineDiffPrefix(type: SkillDiffLine['type']): string {
  if (type === 'removed') return '- '
  if (type === 'added') return '+ '
  return '  '
}
