import { describe, expect, it } from 'vitest'
import { collectSandboxPathMatches, parseSandboxPathDrag } from './sandboxPathChip'

describe('collectSandboxPathMatches', () => {
  it('matches root directories', () => {
    const hits = collectSandboxPathMatches('读 `/workspace` 和 `/skills` 下')
    expect(hits.map((h) => h.path)).toEqual(['/workspace', '/skills'])
  })

  it('matches nested paths', () => {
    const hits = collectSandboxPathMatches('`/workspace/a.py`')
    expect(hits).toHaveLength(1)
    expect(hits[0].path).toBe('/workspace/a.py')
  })
})

describe('parseSandboxPathDrag tick plain', () => {
  it('parses root path token', () => {
    const dt = {
      getData: (type: string) => (type === 'text/plain' ? '`/workspace`' : ''),
    } as DataTransfer
    const payload = parseSandboxPathDrag(dt)
    expect(payload?.path).toBe('/workspace')
    expect(payload?.name).toBe('workspace')
    expect(payload?.isDir).toBe(true)
  })
})
