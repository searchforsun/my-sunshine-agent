import { describe, expect, it } from 'vitest'
import { filterWorkspacePaths, matchWorkspacePathMention } from './workspacePathSuggest'

describe('matchWorkspacePathMention', () => {
  it('matches trailing slash query', () => {
    expect(matchWorkspacePathMention('读 /workspace/test')).toEqual({
      start: 2,
      query: 'workspace/test',
    })
  })

  it('matches bare slash at end', () => {
    expect(matchWorkspacePathMention('请读 /')).toEqual({ start: 3, query: '' })
  })

  it('ignores when slash not at end', () => {
    expect(matchWorkspacePathMention('/workspace 然后')).toBeNull()
  })
})

describe('filterWorkspacePaths', () => {
  const entries = [
    { path: '/workspace', name: 'workspace', isDir: true },
    { path: '/workspace/a.txt', name: 'a.txt', isDir: false },
    { path: '/skills/demo', name: 'demo', isDir: true },
  ]

  it('returns first slice when query empty', () => {
    expect(filterWorkspacePaths(entries, '', 2)).toHaveLength(2)
  })

  it('filters by path segment', () => {
    const out = filterWorkspacePaths(entries, 'a.txt')
    expect(out).toHaveLength(1)
    expect(out[0].path).toBe('/workspace/a.txt')
  })
})
