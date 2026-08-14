import { describe, expect, it } from 'vitest'
import { resolveTimelineStepKind } from './timelineStepIcon'
import type { ProcessingStep } from './processingSteps'

function step(partial: Partial<ProcessingStep>): ProcessingStep {
  return { id: 'x', phase: 'think', lifecycle: 'done', ...partial }
}

describe('resolveTimelineStepKind', () => {
  it('decision / subagent 优先', () => {
    expect(resolveTimelineStepKind(step({ id: 'decision-1', phase: 'decision' }))).toBe('decision')
    expect(resolveTimelineStepKind(step({ id: 'subagent-1', phase: 'subagent' }))).toBe('subagent')
  })

  it('worker / harness plan', () => {
    expect(resolveTimelineStepKind(step({ id: 'worker-1', phase: 'worker' }))).toBe('worker')
    expect(resolveTimelineStepKind(step({ id: 'plan', phase: 'plan' }))).toBe('plan')
    expect(resolveTimelineStepKind(step({ id: 'plan-R2', phase: 'plan' }))).toBe('plan')
  })

  it('rag / intent / skill / tasks / think', () => {
    expect(resolveTimelineStepKind(step({ id: 'rag', phase: 'tool' }))).toBe('rag')
    expect(resolveTimelineStepKind(step({ id: 'rag@1699999999999', phase: 'tool' }))).toBe('rag')
    expect(resolveTimelineStepKind(step({ id: 'i1', phase: 'intent' }))).toBe('intent')
    expect(resolveTimelineStepKind(step({ id: 's1', phase: 'skill' }))).toBe('skill')
    expect(resolveTimelineStepKind(step({ id: 't1', phase: 'tasks' }))).toBe('tasks')
    expect(resolveTimelineStepKind(step({ id: 'think-2', phase: 'think' }))).toBe('think')
  })

  it('工具步按 sandbox 细分', () => {
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__read@1', phase: 'tool' }))).toBe('tool-view')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__edit@2', phase: 'tool' }))).toBe('tool-edit')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__webfetch@3', phase: 'tool' }))).toBe('tool-fetch')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__exec@4', phase: 'tool' }))).toBe('tool-exec')
    expect(resolveTimelineStepKind(step({ id: 'tool-doc-search@5', phase: 'tool' }))).toBe('tool')
  })

  it('其余兜底 generic', () => {
    expect(resolveTimelineStepKind(step({ id: 'i9', phase: 'loop' }))).toBe('generic')
    expect(resolveTimelineStepKind(step({ id: 'node-answer', phase: 'node' }))).toBe('generic')
  })
})
