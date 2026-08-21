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

describe('sortSteps harness worker 按任务序号稳定排序', () => {
  it('并发 worker 按任务序号 T1/T2/T3 排列，忽略 begin startedAt 先后', () => {
    // 模拟 begin 到达顺序乱：t2 先到（startedAt 更小），t1 后到
    const sorted = sortSteps([
      step('worker-t3-1', 'worker', 1003),
      step('worker-t1-1', 'worker', 1001),
      step('worker-t2-1', 'worker', 1002),
    ])
    expect(sorted.map(s => s.id)).toEqual([
      'worker-t1-1',
      'worker-t2-1',
      'worker-t3-1',
    ])
  })

  it('同任务重派版本号升序（t1-1 历史在前，t1-2 新执行在后）', () => {
    const sorted = sortSteps([
      step('worker-t1-2', 'worker', 2000),
      step('worker-t1-1', 'worker', 1000),
    ])
    expect(sorted.map(s => s.id)).toEqual(['worker-t1-1', 'worker-t1-2'])
  })

  it('描述后缀非版本（t1-arch）不参与版本比较', () => {
    const sorted = sortSteps([
      step('worker-t1-arch', 'worker', 1000),
      step('worker-t1-1', 'worker', 1000),
    ])
    expect(sorted.map(s => s.id)).toEqual(['worker-t1-1', 'worker-t1-arch'])
  })

  it('worker 与 think/tool 混排仍按时间线时间戳，worker 不插队', () => {
    const sorted = sortSteps([
      step('worker-t1-1', 'worker', 1000),
      step('tool-plan_submit@1', 'tool', 900),
      step('think-2', 'think', 800),
    ])
    expect(sorted.map(s => s.phase)).toEqual(['think', 'tool', 'worker'])
  })
})
