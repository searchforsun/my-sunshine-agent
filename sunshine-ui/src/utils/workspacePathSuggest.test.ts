import { describe, expect, it } from 'vitest'
import type { SandboxFsNode } from '../api/sandboxWorkspace'
import {
  WorkspacePathSuggestIndex,
  filterWorkspacePaths,
  matchWorkspacePathMention,
  searchFlatPaths,
} from './workspacePathSuggest'

describe('matchWorkspacePathMention', () => {
  it('matches trailing at query', () => {
    expect(matchWorkspacePathMention('读 @workspace/test')).toEqual({
      start: 2,
      query: 'workspace/test',
    })
  })

  it('matches bare at at end', () => {
    expect(matchWorkspacePathMention('请读 @')).toEqual({ start: 3, query: '' })
  })

  it('ignores when at not at end', () => {
    expect(matchWorkspacePathMention('@workspace 然后')).toBeNull()
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

describe('WorkspacePathSuggestIndex（懒加载）', () => {
  const fs: Record<string, SandboxFsNode[]> = {
    '/workspace': [
      { name: 'README', path: '/workspace/README', type: 'file' },
      { name: 'src', path: '/workspace/src', type: 'dir' },
      { name: 'scripts', path: '/workspace/scripts', type: 'dir' },
    ],
    '/workspace/src': [
      { name: 'App.vue', path: '/workspace/src/App.vue', type: 'file' },
      { name: 'main.ts', path: '/workspace/src/main.ts', type: 'file' },
    ],
    '/workspace/scripts': [
      { name: 'build.js', path: '/workspace/scripts/build.js', type: 'file' },
    ],
    '/skills': [
      { name: 'demo', path: '/skills/demo', type: 'dir' },
    ],
    '/skills/demo': [],
  }

  function createIndex() {
    const calls: string[] = []
    const index = new WorkspacePathSuggestIndex((dir) => {
      calls.push(dir)
      return Promise.resolve(fs[dir] ?? [])
    })
    return { index, calls }
  }

  it('空 query：加载第一层 + 第二层子目录，不继续递归', async () => {
    const { index, calls } = createIndex()
    const out = await index.search('')
    expect(calls.sort()).toEqual([
      '/skills',
      '/skills/demo',
      '/workspace',
      '/workspace/scripts',
      '/workspace/src',
    ])
    // 深度优先：第一层目录保持在自身子项之前
    expect(out.map((e) => e.path)).toEqual([
      '/workspace/scripts',
      '/workspace/scripts/build.js',
      '/workspace/src',
      '/workspace/src/App.vue',
      '/workspace/src/main.ts',
      '/workspace/README',
      '/skills/demo',
    ])
  })

  it('输入 src/ap：只加载 /workspace 与 /workspace/src，返回 App.vue', async () => {
    const { index, calls } = createIndex()
    const out = await index.search('src/ap')
    expect(calls.sort()).toEqual(['/workspace', '/workspace/src'])
    expect(out.map((e) => e.path)).toEqual(['/workspace/src/App.vue'])
  })

  it('输入 src/（尾部斜杠）：展示 src 目录全部子项', async () => {
    const { index, calls } = createIndex()
    const out = await index.search('src/')
    expect(calls.sort()).toEqual(['/workspace', '/workspace/src'])
    expect(out.map((e) => e.path)).toEqual(['/workspace/src/App.vue', '/workspace/src/main.ts'])
  })

  it('输入 scripts/build：只加载 scripts 分支', async () => {
    const { index, calls } = createIndex()
    const out = await index.search('scripts/build')
    expect(calls.sort()).toEqual(['/workspace', '/workspace/scripts'])
    expect(out.map((e) => e.path)).toEqual(['/workspace/scripts/build.js'])
  })

  it('目录不存在时退回第一层匹配', async () => {
    const { index, calls } = createIndex()
    const out = await index.search('nope/foo')
    // /workspace 下无 nope，回退到 skills 尝试（同样失败），最终用第一层过滤
    expect(out.map((e) => e.path)).toEqual([])
    expect(calls).toContain('/workspace')
  })

  it('workspace/ 前缀显式限定根', async () => {
    const { index, calls } = createIndex()
    const out = await index.search('workspace/src')
    expect(calls).toEqual(['/workspace'])
    expect(out.map((e) => e.path)).toEqual(['/workspace/src'])
  })
})

describe('searchFlatPaths（全量索引搜索）', () => {
  const paths = [
    '/workspace/wt-abc/README',
    '/workspace/wt-abc/src',
    '/workspace/wt-abc/src/App.vue',
    '/workspace/wt-abc/src/main.ts',
    '/workspace/wt-abc/scripts/build.js',
    '/skills/demo',
  ]

  it('单段关键词可命中任意层级（src 目录与子文件）', () => {
    const out = searchFlatPaths(paths, 'sr')
    expect(out.map((e) => e.path).sort()).toEqual([
      '/workspace/wt-abc/src',
      '/workspace/wt-abc/src/App.vue',
      '/workspace/wt-abc/src/main.ts',
    ])
  })

  it('文件名关键词命中深层文件', () => {
    const out = searchFlatPaths(paths, 'App')
    expect(out.map((e) => e.path)).toEqual(['/workspace/wt-abc/src/App.vue'])
  })

  it('目录判断：存在子路径视为目录，无扩展名文件不算目录', () => {
    const out = searchFlatPaths(paths, 'README')
    expect(out).toHaveLength(1)
    expect(out[0].isDir).toBe(false)
    const dir = searchFlatPaths(paths, 'src')
    expect(dir.find((e) => e.path.endsWith('/src'))?.isDir).toBe(true)
  })

  it('空 query 返回空（不触发全量）', () => {
    expect(searchFlatPaths(paths, '')).toEqual([])
  })
})
