import { describe, expect, it } from 'vitest'
import { applyStepDelta, type ProcessingStep } from './processingSteps'

describe('applyStepDelta · 已存在步骤原地更新', () => {
  it('追加 reasoning 时复用同一 steps 数组引用（避免每 token 新建+sort）', () => {
    let steps = applyStepDelta([], { stepId: 'think', channel: 'reasoning', text: '先' })
    const ref = steps
    steps = applyStepDelta(steps, { stepId: 'think', channel: 'reasoning', text: '后' })
    expect(steps).toBe(ref)
    expect(steps[0].reasoning).toBe('先后')
  })

  it('追加 result/output/step_summary 同样原地更新', () => {
    const steps: ProcessingStep[] = [{
      id: 'node-answer',
      phase: 'node',
      lifecycle: 'running',
      result: 'A',
      output: 'O',
    }]
    const ref = steps
    applyStepDelta(steps, { stepId: 'node-answer', channel: 'result', text: 'B' })
    applyStepDelta(steps, { stepId: 'node-answer', channel: 'output', text: 'P' })
    applyStepDelta(steps, { stepId: 'node-answer', channel: 'step_summary', text: '摘要' })
    expect(steps).toBe(ref)
    expect(steps[0].result).toBe('AB')
    expect(steps[0].output).toBe('OP')
    expect(steps[0].stepSummary).toBe('摘要')
  })

  it('新步骤仍返回含新步的数组（可与原数组不同引用）', () => {
    const steps: ProcessingStep[] = [{
      id: 'intent',
      phase: 'intent',
      lifecycle: 'done',
    }]
    const next = applyStepDelta(steps, { stepId: 'think', channel: 'reasoning', text: '新' })
    expect(next).not.toBe(steps)
    expect(next.map(s => s.id)).toEqual(['intent', 'think'])
    expect(next.find(s => s.id === 'think')?.reasoning).toBe('新')
  })
})
