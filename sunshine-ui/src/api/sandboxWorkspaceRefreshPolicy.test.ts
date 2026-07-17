import { describe, expect, it } from 'vitest'
import { resolveSandboxWorkspaceRefreshScope } from './sandboxWorkspaceRefreshPolicy'
import type { ProcessingStep } from './processingSteps'

function sandboxStep(partial: Partial<ProcessingStep> & { id: string }): ProcessingStep {
  return {
    label: '沙箱',
    lifecycle: 'done',
    ...partial,
  }
}

describe('resolveSandboxWorkspaceRefreshScope', () => {
  it('refreshes workspace after write', () => {
    expect(resolveSandboxWorkspaceRefreshScope(sandboxStep({
      id: 'tool-sandbox__write@1',
      lifecycle: 'done',
    }))).toBe('workspace')
  })

  it('refreshes skills when glob under /skills', () => {
    expect(resolveSandboxWorkspaceRefreshScope(sandboxStep({
      id: 'tool-sandbox__glob@2',
      lifecycle: 'done',
      metadata: { sandboxSearchRoot: '/skills/demo' },
    }))).toBe('skills')
  })

  it('ignores running steps', () => {
    expect(resolveSandboxWorkspaceRefreshScope(sandboxStep({
      id: 'tool-sandbox__write@3',
      lifecycle: 'running',
    }))).toBeNull()
  })

  it('ignores read tool', () => {
    expect(resolveSandboxWorkspaceRefreshScope(sandboxStep({
      id: 'tool-sandbox__read@4',
      lifecycle: 'done',
    }))).toBeNull()
  })
})
