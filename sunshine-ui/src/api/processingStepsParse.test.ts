import { describe, it, expect } from 'vitest'
import { normalizeStep } from './processingStepsParse'

describe('normalizeStep 无 summary 步不丢弃', () => {
  it('worker 骨架步（仅 label+phase，无 summary）应保留而非返回 null', () => {
    const raw = {
      type: 'step', id: 'worker-t1', phase: 'worker', lifecycle: 'running',
      startedAt: 1787107191925, ts: 1787107191925, label: '调研用户代办清单数据来源',
    }
    const step = normalizeStep(raw)
    expect(step).not.toBeNull()
    expect(step!.id).toBe('worker-t1')
    expect(step!.phase).toBe('worker')
    expect(step!.summary).toBeUndefined()
  })

  it('含 subSteps 的 worker 终态步（subSteps 内亦无 summary）应完整保留', () => {
    const raw = {
      type: 'step', id: 'worker-t1', phase: 'worker', lifecycle: 'done',
      label: '调研用户代办清单数据来源',
      subSteps: [
        { id: 'think', phase: 'think', lifecycle: 'done', reasoning: 'x' },
        { id: 'tool-1', phase: 'tool', lifecycle: 'done', result: 'y' },
      ],
    }
    const step = normalizeStep(raw)
    expect(step).not.toBeNull()
    expect(step!.subSteps).toHaveLength(2)
    expect(step!.subSteps![1].result).toBe('y')
  })

  it('有 summary 的普通步不受影响', () => {
    const raw = {
      type: 'step', id: 'think', phase: 'think', lifecycle: 'done',
      summary: { after: '完成' },
    }
    const step = normalizeStep(raw)
    expect(step).not.toBeNull()
    expect(step!.summary?.after).toBe('完成')
  })
})
