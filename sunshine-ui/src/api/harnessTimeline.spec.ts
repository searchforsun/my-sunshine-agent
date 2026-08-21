import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { formatTaskUnitId, isHarnessTimelineMessage, isPlanDagMessage } from './harnessTimeline'

function step(
  partial: Pick<ProcessingStep, 'id' | 'phase'> & Partial<ProcessingStep>,
): ProcessingStep {
  return {
    lifecycle: 'done',
    ...partial,
  }
}

describe('formatTaskUnitId · 执行单元记号', () => {
  it('r5-quality-2 → T5-2（序号取首个数字，版本取末尾 -N）', () => {
    expect(formatTaskUnitId('r5-quality-2')).toBe('T5-2')
  })

  it('t1-1 → T1-1', () => {
    expect(formatTaskUnitId('t1-1')).toBe('T1-1')
  })

  it('t1-arch → T1（描述后缀非版本，不显示）', () => {
    expect(formatTaskUnitId('t1-arch')).toBe('T1')
  })

  it('t1 → T1', () => {
    expect(formatTaskUnitId('t1')).toBe('T1')
  })

  it('空/空白 → 空', () => {
    expect(formatTaskUnitId('')).toBe('')
    expect(formatTaskUnitId('   ')).toBe('')
  })

  it('无数字序号：保留版本后缀', () => {
    expect(formatTaskUnitId('research-codebase-2')).toBe('T-2')
    expect(formatTaskUnitId('research-codebase')).toBe('T')
  })
})

describe('harnessTimeline · DAG vs harness', () => {
  it('classic planId + node-* → DAG', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan', detail: 'planId=ep-1' }),
      step({ id: 'node-a', phase: 'node' }),
      step({ id: 'node-answer', phase: 'node' }),
    ]
    expect(isPlanDagMessage(steps, 'ep-1')).toBe(true)
    expect(isHarnessTimelineMessage(steps)).toBe(false)
  })

  it('executionPlanId + node-* → DAG', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan' }),
      step({ id: 'node-rag', phase: 'node' }),
    ]
    expect(isPlanDagMessage(steps, 'plan-xyz')).toBe(true)
    expect(isHarnessTimelineMessage(steps, 'plan-xyz')).toBe(false)
  })

  it('harness：plan 无 DAG 引用 → harness，非 DAG', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan', summary: { after: '规划 R1' } }),
      step({ id: 'plan-R2', phase: 'plan', summary: { after: '规划 R2' } }),
    ]
    expect(isPlanDagMessage(steps)).toBe(false)
    expect(isHarnessTimelineMessage(steps)).toBe(true)
  })

  it('harness：worker-* / phase=worker → harness', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan' }),
      step({ id: 'worker-t1', phase: 'worker', summary: { after: '任务 A' } }),
    ]
    expect(isPlanDagMessage(steps)).toBe(false)
    expect(isHarnessTimelineMessage(steps)).toBe(true)
  })

  it('历史：仅 planId 合成 plan、无 worker → 仍可 DAG', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan', detail: 'planId=hist-1', summary: { after: '执行计划' } }),
    ]
    expect(isPlanDagMessage(steps, 'hist-1')).toBe(true)
    expect(isHarnessTimelineMessage(steps, 'hist-1')).toBe(false)
  })

  it('互斥：executionPlanId + plan 无 worker → DAG 胜，二者不能同为 true', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan', summary: { after: '规划' } }),
    ]
    const executionPlanId = 'ep-hist-only'
    expect(isPlanDagMessage(steps, executionPlanId)).toBe(true)
    expect(isHarnessTimelineMessage(steps, executionPlanId)).toBe(false)
    expect(
      isPlanDagMessage(steps, executionPlanId)
      && isHarnessTimelineMessage(steps, executionPlanId),
    ).toBe(false)
  })

  it('ReAct 无 plan/worker → 皆 false', () => {
    const steps = [
      step({ id: 'intent', phase: 'intent' }),
      step({ id: 'think', phase: 'think' }),
      step({ id: 'tool-1', phase: 'tool' }),
    ]
    expect(isPlanDagMessage(steps)).toBe(false)
    expect(isHarnessTimelineMessage(steps)).toBe(false)
  })
})
