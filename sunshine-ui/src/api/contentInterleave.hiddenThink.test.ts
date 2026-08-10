import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { isHiddenReactTimelineStep } from './contentInterleave'

function think(overrides: Partial<ProcessingStep> = {}): ProcessingStep {
  return { id: 'think-2', phase: 'think', lifecycle: 'done', ...overrides }
}

describe('isHiddenReactTimelineStep · 空深度思考', () => {
  it('无 reasoning 且无 stepSummary 的 think 步隐藏', () => {
    expect(isHiddenReactTimelineStep(think())).toBe(true)
    expect(isHiddenReactTimelineStep(think({ reasoning: '  ', stepSummary: '' }))).toBe(true)
  })

  it('有 reasoning 的 think 步展示', () => {
    expect(isHiddenReactTimelineStep(think({ reasoning: '先拆任务' }))).toBe(false)
  })

  it('仅有 think_summary（stepSummary）也展示', () => {
    expect(isHiddenReactTimelineStep(think({ stepSummary: '核对 applyTheme' }))).toBe(false)
  })

  it('generate 仍隐藏', () => {
    expect(isHiddenReactTimelineStep({
      id: 'generate',
      phase: 'generate',
      lifecycle: 'done',
    })).toBe(true)
  })

  it('非 think 步不受影响', () => {
    expect(isHiddenReactTimelineStep({
      id: 'tool-1',
      phase: 'tool',
      lifecycle: 'done',
    })).toBe(false)
  })
})
