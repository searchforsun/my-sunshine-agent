import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'
import {
  appendInterleavedContent,
  contentRowsAfterStep,
  resolveCollapsedAnswerText,
  shouldRenderPlanMainContentBlock,
} from './contentInterleave'

function step(
  partial: Pick<ProcessingStep, 'id' | 'phase'> & Partial<ProcessingStep>,
): ProcessingStep {
  return { lifecycle: 'done', ...partial }
}

function msg(partial: Partial<ChatMessage>): ChatMessage {
  return { role: 'assistant', content: '', ...partial }
}

describe('contentInterleave · harness vs plan-workflow', () => {
  const harnessSteps: ProcessingStep[] = [
    step({ id: 'intent', phase: 'intent' }),
    step({ id: 'plan', phase: 'plan', summary: { after: '规划 R1' } }),
    step({ id: 'worker-t1', phase: 'worker', summary: { after: '任务 A' } }),
    step({ id: 'planner-answer', phase: 'answer', summary: { after: '综合回答' } }),
  ]

  const dagSteps: ProcessingStep[] = [
    step({
      id: 'plan',
      phase: 'plan',
      metadata: {
        planApproval: {
          planGraph: {
            nodes: [{ id: 'n1', type: 'llm', displayName: 'A' }],
            edges: [],
          },
        },
      },
    }),
    step({ id: 'node-rag', phase: 'node', detail: '检索中' }),
    step({ id: 'node-answer', phase: 'node', result: '计划终稿' }),
  ]

  it('harness：worker 无 graph → 非 plan-workflow，正文可挂在 worker 后（ReAct 式）', () => {
    const blocks = [
      { segmentId: 'content-1', afterStepId: 'plan', text: '规划说明' },
      { segmentId: 'content-2', afterStepId: 'worker-t1', text: 'Worker 产出' },
      { segmentId: 'content-3', afterStepId: 'planner-answer', text: '综合回答正文' },
    ]
    const visible = new Set(harnessSteps.map(s => s.id))
    const opts = { live: false, lastBlockIndex: 2 }

    expect(shouldRenderPlanMainContentBlock(blocks[1], harnessSteps)).toBe(true)
    expect(contentRowsAfterStep('worker-t1', harnessSteps, visible, blocks, opts).map(r => r.text))
      .toEqual(['Worker 产出'])
    expect(contentRowsAfterStep('planner-answer', harnessSteps, visible, blocks, opts).map(r => r.text))
      .toEqual(['综合回答正文'])
  })

  it('harness：折叠终稿走 ReAct 穿插，不锚定 node-answer', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '全文',
      steps: harnessSteps,
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'plan', text: '过程' },
        { segmentId: 'content-2', afterStepId: 'worker-t1', text: 'Worker 段' },
        { segmentId: 'content-3', afterStepId: 'planner-answer', text: '终稿段落' },
      ],
    }))).toBe('终稿段落')
  })

  it('harness：plain content 不因 phase=plan 被丢弃', () => {
    const m = msg({ steps: harnessSteps, content: '' })
    appendInterleavedContent(m, '增量正文', 'worker-t1')
    expect(m.content).toBe('增量正文')
    expect(m.contentBlocks).toEqual([
      { segmentId: 'tail:worker-t1', afterStepId: 'worker-t1', text: '增量正文' },
    ])
  })

  it('plan-workflow DAG：仍仅穿插 node-answer，业务 node 块不进主时间线', () => {
    const blocks = [
      { segmentId: 'leak', afterStepId: 'node-rag', text: '抽屉摘要' },
      { segmentId: 'tail:node-answer', afterStepId: 'node-answer', text: '块' },
    ]
    const visible = new Set(dagSteps.map(s => s.id))
    const opts = { live: false, lastBlockIndex: 1 }

    expect(shouldRenderPlanMainContentBlock(blocks[0], dagSteps)).toBe(false)
    expect(contentRowsAfterStep('node-rag', dagSteps, visible, blocks, opts)).toEqual([])
    expect(contentRowsAfterStep('node-answer', dagSteps, visible, blocks, opts).map(r => r.text))
      .toEqual(['计划终稿'])
  })

  it('plan-workflow DAG：折叠终稿走 answer SSOT', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '误入',
      steps: dagSteps,
      contentBlocks: [{ segmentId: 'tail:node-answer', afterStepId: 'node-answer', text: '块' }],
    }))).toBe('计划终稿')
  })
})
