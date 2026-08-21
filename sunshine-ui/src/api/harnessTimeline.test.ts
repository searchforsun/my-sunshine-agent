import { describe, it, expect } from 'vitest'
import { isHarnessTimelineMessage } from './harnessTimeline'
import type { ProcessingStep } from './processingSteps'

function step(partial: Partial<ProcessingStep>): ProcessingStep {
  return { id: 'x', phase: 'think', lifecycle: 'done', ...partial } as ProcessingStep
}

describe('isHarnessTimelineMessage', () => {
  it('worker 步 → harness', () => {
    const steps = [
      step({ id: 'think', phase: 'think' }),
      step({ id: 'worker-t1', phase: 'worker', lifecycle: 'running' }),
    ]
    expect(isHarnessTimelineMessage(steps, undefined)).toBe(true)
  })

  it('无 worker 步但存在 planner 元工具步（plan_submit）→ harness（pro 主对话不折叠）', () => {
    const steps = [
      step({ id: 'think', phase: 'think', reasoning: 'x' }),
      step({ id: 'tool-plan_submit@1787062111706', phase: 'tool', lifecycle: 'done' }),
      step({ id: 'think-2', phase: 'think', reasoning: 'y' }),
      step({ id: 'tool-self_assess@1787062119102', phase: 'tool', lifecycle: 'done' }),
      step({ id: 'think-3', phase: 'think', reasoning: 'z' }),
    ]
    expect(isHarnessTimelineMessage(steps, undefined)).toBe(true)
  })

  it('无 worker/plan/planner 元工具（fast 普通 ReAct）→ 非 harness，走 roundGroup 折叠', () => {
    const steps = [
      step({ id: 'think', phase: 'think', reasoning: 'x' }),
      step({ id: 'tool-sdk__oa__list_tasks@1', phase: 'tool', lifecycle: 'done' }),
      step({ id: 'think-2', phase: 'think', reasoning: 'y' }),
      step({ id: 'generate', phase: 'generate' }),
    ]
    expect(isHarnessTimelineMessage(steps, undefined)).toBe(false)
  })

  it('静态 Workflow DAG（executionPlanId + node-*）优先于 harness 信号', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan' }),
      step({ id: 'node-a', phase: 'node' }),
    ]
    expect(isHarnessTimelineMessage(steps, 'plan-123')).toBe(false)
  })

  it('DAG 判定在 harness worker 存在时让路（互斥：有 worker 无图 → harness）', () => {
    const steps = [
      step({ id: 'plan', phase: 'plan' }),
      step({ id: 'worker-t1', phase: 'worker' }),
    ]
    expect(isHarnessTimelineMessage(steps, 'plan-123')).toBe(true)
  })
})
