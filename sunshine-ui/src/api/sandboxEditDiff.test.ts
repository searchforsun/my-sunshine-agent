import { describe, expect, it } from 'vitest'
import {
  writeContentAsAddLines,
  linesFromEditDiffMeta,
  summarizeDiffCounts,
} from './sandboxEditDiff'

describe('sandboxEditDiff', () => {
  it('writeContentAsAddLines assigns newLine 1..N', () => {
    const lines = writeContentAsAddLines('a\nb\n')
    expect(lines).toEqual([
      { kind: 'add', text: 'a', oldLine: null, newLine: 1 },
      { kind: 'add', text: 'b', oldLine: null, newLine: 2 },
    ])
    expect(summarizeDiffCounts(lines)).toEqual({ add: 2, del: 0 })
  })

  it('linesFromEditDiffMeta maps structured metadata', () => {
    const lines = linesFromEditDiffMeta({
      path: '/x.py',
      contextRadius: 3,
      lines: [
        { kind: 'ctx', text: 'a', oldLine: 1, newLine: 1 },
        { kind: 'del', text: 'b', oldLine: 2, newLine: null },
        { kind: 'add', text: 'c', oldLine: null, newLine: 2 },
        { kind: 'fold', text: '', oldLine: null, newLine: null },
      ],
    })
    expect(lines?.map(l => l.kind)).toEqual(['ctx', 'del', 'add', 'fold'])
  })

  it('linesFromEditDiffMeta returns null when missing', () => {
    expect(linesFromEditDiffMeta(undefined)).toBeNull()
  })
})
