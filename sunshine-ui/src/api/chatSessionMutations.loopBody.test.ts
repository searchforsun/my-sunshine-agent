import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { updateNodeStepContent } from './chatSessionMutations'

describe('updateNodeStepContent loop body', () => {
  it('writes contentBlocks into i{n}-node-* nested under loop', () => {
    const steps: ProcessingStep[] = [
      {
        id: 'node-loop-1',
        phase: 'node',
        lifecycle: 'running',
        label: '条件循环',
        subSteps: [
          {
            id: 'i1-node-agent-1',
            phase: 'node',
            lifecycle: 'running',
            label: '综合分析',
            subSteps: [{ id: 'think', phase: 'think', lifecycle: 'done' }],
          },
        ],
      },
    ]
    const next = updateNodeStepContent(steps, 'i1-node-agent-1', step => {
      step.contentBlocks = [{ segmentId: 's1', afterStepId: 'think', text: '流式正文' }]
    })
    expect(next[0].subSteps?.[0].contentBlocks?.[0].text).toBe('流式正文')
    expect(next[0].subSteps?.[0].subSteps?.[0].id).toBe('think')
  })
})

describe('updateNodeStepContent reference stability', () => {
  it('keeps subSteps array reference when mutating contentBlocks only', () => {
    const thinkA = { id: 'think', phase: 'think', lifecycle: 'done' } as ProcessingStep
    const toolB = { id: 'tool-sandbox__read@1', phase: 'tool', lifecycle: 'done' } as ProcessingStep
    const steps: ProcessingStep[] = [
      {
        id: 'subagent-1',
        phase: 'subagent',
        lifecycle: 'running',
        subSteps: [thinkA, toolB],
      },
    ]
    const next = updateNodeStepContent(steps, 'subagent-1', step => {
      step.contentBlocks = [{ segmentId: 's1', afterStepId: 'think', text: '增量' }]
    })
    // 正文增量只复制正文载体；子步骤数组与元素引用保持稳定，
    // 抽屉 OperationStack 依赖引用稳定跳过全量 patch（避免每 token 重渲染所有子卡片）
    expect(next[0].subSteps).toBe(steps[0].subSteps)
    expect(next[0].subSteps?.[0]).toBe(thinkA)
    expect(next[0].subSteps?.[1]).toBe(toolB)
  })

  it('does not rebuild sibling branches when target step is elsewhere', () => {
    const aSub = [{ id: 'think-a', phase: 'think', lifecycle: 'done' } as ProcessingStep]
    const bSub = [{ id: 'think-b', phase: 'think', lifecycle: 'done' } as ProcessingStep]
    const steps: ProcessingStep[] = [
      { id: 'node-a', phase: 'node', lifecycle: 'running', subSteps: aSub },
      { id: 'node-b', phase: 'node', lifecycle: 'running', subSteps: bSub },
    ]
    const next = updateNodeStepContent(steps, 'node-a', step => {
      step.contentBlocks = [{ segmentId: 's1', afterStepId: 'think-a', text: 'x' }]
    })
    // 目标节点复制正文载体；无关兄弟分支引用不变
    expect(next[0]).not.toBe(steps[0])
    expect(next[1]).toBe(steps[1])
    expect(next[1].subSteps).toBe(bSub)
  })
})
