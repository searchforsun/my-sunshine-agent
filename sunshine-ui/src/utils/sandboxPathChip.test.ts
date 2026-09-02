import { beforeEach, describe, expect, it } from 'vitest'
import {
  collectSandboxPathMatches,
  matchSandboxPathByIndex,
  parseSandboxPathDrag,
} from './sandboxPathChip'

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

  it('matches path with line range', () => {
    const hits = collectSandboxPathMatches('`/workspace/a.py` L120-125')
    expect(hits).toHaveLength(1)
    expect(hits[0].path).toBe('/workspace/a.py')
    expect(hits[0].lineStart).toBe(120)
    expect(hits[0].lineEnd).toBe(125)
    expect(hits[0].end).toBe('`/workspace/a.py` L120-125'.length)
  })

  it('matches path with single line', () => {
    const hits = collectSandboxPathMatches('见 `/workspace/scripts/build.mjs` L3 处')
    expect(hits).toHaveLength(1)
    expect(hits[0].path).toBe('/workspace/scripts/build.mjs')
    expect(hits[0].lineStart).toBe(3)
    expect(hits[0].lineEnd).toBeUndefined()
  })

  it('plain path token stays without line suffix', () => {
    const hits = collectSandboxPathMatches('读 `/workspace/README` 和 `/workspace/src/a.ts` L10')
    expect(hits.map((h) => h.path)).toEqual(['/workspace/README', '/workspace/src/a.ts'])
    expect(hits[1].lineStart).toBe(10)
  })
})

describe('matchSandboxPathByIndex', () => {
  const ROOT = '/workspace/wt-test'
  const INDEX = new Set([
    '/workspace/wt-test/scripts/README.md',
    '/workspace/wt-test/scripts/build.mjs',
    '/workspace/wt-test/example',
    '/workspace/wt-test/example/sub.md',
    '/workspace/wt-test/README.md',
  ])

  beforeEach(() => {
    ;(globalThis as any).window = globalThis
    ;(window as any).__smd_sandboxIndex = new Set(INDEX)
  })

  it('resolves file relative path', () => {
    const { resolved, hit } = matchSandboxPathByIndex('scripts/README.md', ROOT)
    expect(hit).toBe(true)
    expect(resolved).toBe('/workspace/wt-test/scripts/README.md')
  })

  it('resolves trailing-slash directory by prefix', () => {
    const { resolved, hit } = matchSandboxPathByIndex('example/', ROOT)
    expect(hit).toBe(true)
    expect(resolved).toBe('/workspace/wt-test/example')
  })

  it('resolves absolute trailing-slash directory', () => {
    const { resolved, hit } = matchSandboxPathByIndex('/workspace/wt-test/example/', ROOT)
    expect(hit).toBe(true)
    expect(resolved).toBe('/workspace/wt-test/example')
  })

  it('rejects unknown trailing-slash path', () => {
    const { resolved, hit } = matchSandboxPathByIndex('nope/', ROOT)
    expect(hit).toBe(false)
    expect(resolved).toBe('')
  })

  it('falls back to single basename match', () => {
    const { resolved, hit } = matchSandboxPathByIndex('build.mjs', ROOT)
    expect(hit).toBe(true)
    expect(resolved).toBe('/workspace/wt-test/scripts/build.mjs')
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
