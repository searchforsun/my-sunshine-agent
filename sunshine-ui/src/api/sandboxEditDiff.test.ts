import { describe, expect, it } from 'vitest'
import {
  formatDiffLinesAsText,
  lineUnifiedDiff,
  parseSandboxEditDiff,
  summarizeDiffCounts,
  writeContentAsAddLines,
} from './sandboxEditDiff'

describe('sandboxEditDiff', () => {
  it('lineUnifiedDiff keeps shared lines as ctx', () => {
    const lines = lineUnifiedDiff('a\nb\nc', 'a\nx\nc')
    expect(formatDiffLinesAsText(lines)).toBe(' a\n-b\n+x\n c')
  })

  it('rejects non-unified legacy markers', () => {
    const raw = '<<< old\nid,amount\n1,150.50\n\n>>> new\nid,amount\n1,999.00'
    expect(parseSandboxEditDiff(raw)).toBeNull()
  })

  it('parses already-prefixed +- text', () => {
    const lines = parseSandboxEditDiff('-foo\n+bar\n baz')
    expect(lines).toEqual([
      { kind: 'del', text: 'foo' },
      { kind: 'add', text: 'bar' },
      { kind: 'ctx', text: 'baz' },
    ])
  })

  it('summarizeDiffCounts', () => {
    expect(summarizeDiffCounts([
      { kind: 'ctx', text: 'a' },
      { kind: 'del', text: 'b' },
      { kind: 'add', text: 'c' },
      { kind: 'add', text: 'd' },
    ])).toEqual({ add: 2, del: 1 })
  })

  it('writeContentAsAddLines counts lines as adds', () => {
    const lines = writeContentAsAddLines('a\nb\nc\n')
    expect(lines.every(l => l.kind === 'add')).toBe(true)
    expect(lines.map(l => l.text)).toEqual(['a', 'b', 'c'])
    expect(summarizeDiffCounts(lines)).toEqual({ add: 3, del: 0 })
  })
})
