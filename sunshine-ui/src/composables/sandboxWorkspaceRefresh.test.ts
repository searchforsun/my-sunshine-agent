import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearSandboxWorkspaceRefreshPending,
  flushSandboxWorkspaceRefresh,
  requestSandboxWorkspaceRefresh,
  sandboxWorkspaceRefresh,
} from '../composables/sandboxWorkspaceRefresh'

describe('requestSandboxWorkspaceRefresh debounce', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    clearSandboxWorkspaceRefreshPending()
    sandboxWorkspaceRefresh.tick = 0
    sandboxWorkspaceRefresh.conversationId = null
    sandboxWorkspaceRefresh.scope = 'workspace'
  })

  afterEach(() => {
    clearSandboxWorkspaceRefreshPending()
    vi.useRealTimers()
  })

  it('coalesces many writes into one tick', () => {
    for (let i = 0; i < 20; i++) {
      requestSandboxWorkspaceRefresh('c1', 'workspace')
    }
    expect(sandboxWorkspaceRefresh.tick).toBe(0)
    vi.advanceTimersByTime(400)
    expect(sandboxWorkspaceRefresh.tick).toBe(1)
    expect(sandboxWorkspaceRefresh.conversationId).toBe('c1')
    expect(sandboxWorkspaceRefresh.scope).toBe('workspace')
  })

  it('flushes distinct scopes once each', () => {
    requestSandboxWorkspaceRefresh('c1', 'workspace')
    requestSandboxWorkspaceRefresh('c1', 'skills')
    vi.advanceTimersByTime(400)
    expect(sandboxWorkspaceRefresh.tick).toBe(2)
  })

  it('immediate bypasses debounce', () => {
    requestSandboxWorkspaceRefresh('c1', 'skills', true)
    expect(sandboxWorkspaceRefresh.tick).toBe(1)
  })

  it('flushSandboxWorkspaceRefresh drains pending', () => {
    requestSandboxWorkspaceRefresh('c1', 'workspace')
    flushSandboxWorkspaceRefresh('c1')
    expect(sandboxWorkspaceRefresh.tick).toBe(1)
  })
})
