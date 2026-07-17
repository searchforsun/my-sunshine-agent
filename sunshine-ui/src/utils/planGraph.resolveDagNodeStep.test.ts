import { describe, expect, it } from 'vitest'
import type { PlanGraph } from '../api/executionPlans'
import type { ProcessingStep } from '../api/processingSteps'
import { resolveDagNodeStep } from './planGraph'

describe('resolveDagNodeStep', () => {
  const graph: PlanGraph = {
    nodes: [
      { id: 'loop-1', type: 'loop', displayName: '条件循环', params: {} },
      { id: 'agent-1', type: 'agent', parentId: 'loop-1', displayName: '综合分析', params: { skill: 'finance-analysis' } },
    ],
    edges: [
      { from: 'start', to: 'loop-1' },
      { from: 'loop-1', to: 'answer' },
    ],
  }

  it('resolves loop body agent from parent.subSteps', () => {
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
            detail: '已加载技能：finance-analysis',
            subSteps: [
              { id: 'think', phase: 'think', lifecycle: 'running', summary: { active: '分析中' } },
            ],
            contentBlocks: [{ segmentId: 'c1', afterStepId: 'think', text: '正文片段' }],
          },
        ],
      },
    ]
    const step = resolveDagNodeStep('agent-1', steps, graph)
    expect(step?.id).toBe('i1-node-agent-1')
    expect(step?.subSteps).toHaveLength(1)
    expect(step?.contentBlocks?.[0]?.text).toBe('正文片段')
  })
})
