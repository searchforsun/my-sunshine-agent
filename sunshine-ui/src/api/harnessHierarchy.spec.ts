import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import {
  buildHarnessTimelineEntries,
  isHarnessPlanStep,
  isWorkerStep,
  resolveWorkerHandoffText,
} from './harnessHierarchy'

function step(
  partial: Pick<ProcessingStep, 'id' | 'phase'> & Partial<ProcessingStep>,
): ProcessingStep {
  return {
    lifecycle: 'done',
    ...partial,
  }
}

describe('harnessHierarchy', () => {
  it('isWorkerStep / isHarnessPlanStep', () => {
    expect(isWorkerStep({ id: 'worker-t1', phase: 'worker' })).toBe(true)
    expect(isWorkerStep({ id: 'worker-x', phase: 'tool' })).toBe(true)
    expect(isWorkerStep({ id: 'think', phase: 'think' })).toBe(false)
    expect(isHarnessPlanStep({ id: 'plan', phase: 'plan' })).toBe(true)
    expect(isHarnessPlanStep({ id: 'plan-R2', phase: 'plan' })).toBe(true)
    expect(isHarnessPlanStep({ id: 'plan-R2', phase: 'think' })).toBe(true)
    expect(isHarnessPlanStep({ id: 'intent', phase: 'intent' })).toBe(false)
  })

  it('handoff：done worker 用 result/detail/summary.after；running 无手递', () => {
    expect(resolveWorkerHandoffText(step({
      id: 'worker-1',
      phase: 'worker',
      label: '调研',
      lifecycle: 'running',
      summary: { active: '执行中' },
    }))).toBeUndefined()

    expect(resolveWorkerHandoffText(step({
      id: 'worker-2',
      phase: 'worker',
      label: '调研',
      lifecycle: 'done',
      result: 'handoff：发现 Q2 下滑主因',
      detail: 'done',
      summary: { after: 'done' },
    }))).toBe('handoff：发现 Q2 下滑主因')

    expect(resolveWorkerHandoffText(step({
      id: 'worker-3',
      phase: 'worker',
      label: '调研',
      lifecycle: 'done',
      detail: 'status-ok',
      summary: { after: 'status-ok' },
    }))).toBe('status-ok')
  })

  it('buildHarnessTimelineEntries 保留原序并携带 handoff', () => {
    const entries = buildHarnessTimelineEntries([
      step({ id: 'plan', phase: 'plan', label: '规划 R1' }),
      step({
        id: 'worker-a',
        phase: 'worker',
        label: '任务 A',
        lifecycle: 'done',
        result: '完成摘要',
      }),
    ])
    expect(entries).toHaveLength(2)
    expect(entries[0]).toMatchObject({ handoffText: undefined })
    expect(entries[0].step.id).toBe('plan')
    expect(entries[1]).toMatchObject({ handoffText: '完成摘要' })
    expect(entries[1].step.id).toBe('worker-a')
  })
})
