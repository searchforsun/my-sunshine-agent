import { describe, expect, it } from 'vitest'
import type { ConfigVersionSummary } from '../api/ragAdmin'
import { findNewestConfigVersion, sortConfigVersionsDesc } from './kbConfigVersion'

function ver(partial: Partial<ConfigVersionSummary> & Pick<ConfigVersionSummary, 'id' | 'versionNo'>): ConfigVersionSummary {
  return {
    status: 'published',
    createdAt: '2026-07-03T09:00:39Z',
    publishedAt: '2026-07-03T09:00:39Z',
    active: false,
    targetRecallAt5: null,
    changeNote: null,
    createdBy: null,
    ...partial,
  }
}

describe('sortConfigVersionsDesc', () => {
  it('orders by display time newest first', () => {
    const older = ver({
      id: 1,
      versionNo: 1,
      publishedAt: '2026-07-03T09:00:39Z',
      createdAt: '2026-07-03T09:00:39Z',
    })
    const newer = ver({
      id: 2,
      versionNo: 2,
      publishedAt: '2026-07-03T16:53:39Z',
      createdAt: '2026-07-03T16:53:39Z',
      active: true,
      status: 'active',
    })
    expect(sortConfigVersionsDesc([older, newer]).map((v) => v.id)).toEqual([2, 1])
    expect(sortConfigVersionsDesc([newer, older]).map((v) => v.id)).toEqual([2, 1])
  })
})

describe('findNewestConfigVersion', () => {
  it('defaults to newest even when an older pipeline version exists', () => {
    const failed = ver({
      id: 1,
      versionNo: 1,
      status: 'eval_failed',
      publishedAt: '2026-07-03T09:00:39Z',
      createdAt: '2026-07-03T09:00:39Z',
    })
    const live = ver({
      id: 2,
      versionNo: 2,
      status: 'active',
      active: true,
      publishedAt: '2026-07-03T16:53:39Z',
      createdAt: '2026-07-03T16:53:39Z',
    })
    expect(findNewestConfigVersion([failed, live])?.id).toBe(2)
  })
})
