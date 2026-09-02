import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { shouldShowAfterThinkPendingHint, shouldShowPendingHintForLastRow } from './contentInterleave'

function think(overrides: Partial<ProcessingStep> = {}): ProcessingStep {
  return { id: 'think', phase: 'think', lifecycle: 'running', ...overrides }
}

function tool(): ProcessingStep {
  return { id: 'tool-1', phase: 'tool', lifecycle: 'running', label: 'read' }
}

describe('shouldShowAfterThinkPendingHint', () => {
  it('hides while think is still running (思考中不提示)', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: think(),
    })).toBe(false)
  })

  it('shows when last step is a done think awaiting tool start', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: think({ lifecycle: 'done' }),
    })).toBe(true)
  })

  it('shows when last step is a done tool (model generating next tool params)', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: { id: 'tool-1', phase: 'tool', lifecycle: 'done', label: 'write' },
    })).toBe(true)
  })

  it('hides while tool step is still running (自带 pulse)', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: tool(),
    })).toBe(false)
  })

  it('shows even when done step is followed by (already streamed) content', () => {
    // 历史正文已输出完毕、模型仍在生成 tool 参数：占位不应被旧正文抑制
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: think({ lifecycle: 'done' }),
    })).toBe(true)
  })

  it('hides when message is terminal (not processing)', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: false,
      lastStep: think(),
    })).toBe(false)
  })

  it('hides when last step is running (e.g. tasks board)', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: { id: 'tasks', phase: 'tasks', lifecycle: 'running' },
    })).toBe(false)
  })

  it('shows when last step is a done tasks board awaiting next step', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: { id: 'tasks', phase: 'tasks', lifecycle: 'done' },
    })).toBe(true)
  })

  it('hides when no step row exists', () => {
    expect(shouldShowAfterThinkPendingHint({
      processing: true,
      lastStep: undefined,
    })).toBe(false)
  })
})

describe('shouldShowPendingHintForLastRow（时间线末行工具组）', () => {
  it('hides when no row exists', () => {
    expect(shouldShowPendingHintForLastRow({
      processing: true,
    })).toBe(false)
  })

  it('hides when trailing row is a tool group with a running step (自带 pulse)', () => {
    const group = {
      kind: 'toolGroup' as const,
      steps: [
        { id: 'tool-w1', phase: 'tool' as const, lifecycle: 'done' as const, label: 'write' },
        { id: 'tool-w2', phase: 'tool' as const, lifecycle: 'running' as const, label: 'write' },
      ],
      anyRunning: true,
    }
    expect(shouldShowPendingHintForLastRow({
      processing: true,
      lastRow: group,
    })).toBe(false)
  })

  it('shows when trailing tool group is all done (写文件×3 后生成下一参数空档)', () => {
    const group = {
      kind: 'toolGroup' as const,
      steps: [
        { id: 'tool-w1', phase: 'tool' as const, lifecycle: 'done' as const, label: 'write' },
        { id: 'tool-w2', phase: 'tool' as const, lifecycle: 'done' as const, label: 'write' },
      ],
      anyRunning: false,
    }
    expect(shouldShowPendingHintForLastRow({
      processing: true,
      lastRow: group,
    })).toBe(true)
  })

  it('shows for all-done tool group even when followed by content', () => {
    const group = {
      kind: 'toolGroup' as const,
      steps: [
        { id: 'tool-w1', phase: 'tool' as const, lifecycle: 'done' as const, label: 'write' },
      ],
      anyRunning: false,
    }
    expect(shouldShowPendingHintForLastRow({
      processing: true,
      lastRow: group,
    })).toBe(true)
  })

  it('delegates plain step rows to shouldShowAfterThinkPendingHint', () => {
    expect(shouldShowPendingHintForLastRow({
      processing: true,
      lastRow: { kind: 'step', step: think({ lifecycle: 'done' }) },
    })).toBe(true)
    expect(shouldShowPendingHintForLastRow({
      processing: true,
      lastRow: { kind: 'step', step: think() },
    })).toBe(false)
  })
})
