import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { resolveAgentNodeStepForDrawer } from './hitlSteps'

describe('resolveAgentNodeStepForDrawer loop body', () => {
  it('resolves agent from loop.subSteps for live drawer', () => {
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
            subSteps: [{ id: 'think', phase: 'think', lifecycle: 'running', summary: { active: '分析中' } }],
            contentBlocks: [{ segmentId: 'c1', afterStepId: 'think', text: '片段' }],
          },
        ],
      },
    ]
    const step = resolveAgentNodeStepForDrawer(steps, 'agent-1')
    expect(step?.id).toBe('i1-node-agent-1')
    expect(step?.subSteps?.[0].id).toBe('think')
    expect(step?.contentBlocks?.[0].text).toBe('片段')
  })

  it('resolves harness worker step by worker-{taskId} id for live drawer', () => {
    const steps: ProcessingStep[] = [
      {
        id: 'plan',
        phase: 'plan',
        lifecycle: 'done',
        label: '调度计划',
      },
      {
        id: 'worker-t1',
        phase: 'worker',
        lifecycle: 'running',
        label: '调研仓库',
        metadata: { spawnPrompt: '## 任务目标\n调研仓库' },
        subSteps: [{ id: 'think', phase: 'think', lifecycle: 'running', summary: { active: '分析中' } }],
        contentBlocks: [{ segmentId: 'c1', afterStepId: 'think', text: '片段' }],
      },
    ]
    const step = resolveAgentNodeStepForDrawer(steps, 'worker-t1')
    expect(step?.id).toBe('worker-t1')
    expect(step?.metadata?.spawnPrompt).toContain('任务目标')
    expect(step?.subSteps?.[0].id).toBe('think')
    expect(step?.contentBlocks?.[0].text).toBe('片段')
  })
})
