import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { sortSteps } from './processingStepsNormalize'

function step(
  id: string,
  phase: ProcessingStep['phase'],
  startedAt: number,
): ProcessingStep {
  return { id, phase, lifecycle: 'done', ts: startedAt, startedAt } as ProcessingStep
}

describe('sortSteps skill/tasks 头部钉扎', () => {
  it('skill 固定为 intent 之后第二步，即使 startedAt 更晚', () => {
    const sorted = sortSteps([
      step('intent', 'intent', 100),
      step('think', 'think', 200),
      step('skill', 'skill', 500),
      step('tool-1', 'tool', 300),
    ])
    expect(sorted.map(s => s.phase)).toEqual(['intent', 'skill', 'think', 'tool'])
  })

  it('有 skill 时 tasks 紧随 skill，不再抢第二步', () => {
    const sorted = sortSteps([
      step('intent', 'intent', 100),
      step('tasks', 'tasks', 400),
      step('think', 'think', 200),
      step('skill', 'skill', 500),
    ])
    expect(sorted.map(s => s.phase)).toEqual(['intent', 'skill', 'tasks', 'think'])
  })

  it('无 skill 时 tasks 仍紧随 intent', () => {
    const sorted = sortSteps([
      step('intent', 'intent', 100),
      step('think', 'think', 200),
      step('tasks', 'tasks', 400),
    ])
    expect(sorted.map(s => s.phase)).toEqual(['intent', 'tasks', 'think'])
  })
})
