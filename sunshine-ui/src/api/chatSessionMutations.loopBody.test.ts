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
