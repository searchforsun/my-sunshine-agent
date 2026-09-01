import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import {
  buildContentRowsIndex,
  contentRowsAfterStep,
  orphanContentRows,
} from './contentInterleave'

function step(
  partial: Pick<ProcessingStep, 'id' | 'phase'> & Partial<ProcessingStep>,
): ProcessingStep {
  return { lifecycle: 'done', ...partial }
}

describe('buildContentRowsIndex · 一次遍历锚定索引', () => {
  const steps: ProcessingStep[] = [
    step({ id: 'intent', phase: 'intent' }),
    step({ id: 'think', phase: 'think', reasoning: '分析' }),
    step({ id: 'tool-exec-1', phase: 'tool' }),
    step({ id: 'think-2', phase: 'think', reasoning: '继续' }),
    step({ id: 'tool-exec-2', phase: 'tool' }),
  ]

  it('byStep 按显示锚点分组，与 contentRowsAfterStep 逐行结果一致', () => {
    const blocks = [
      { segmentId: 'content-1', afterStepId: 'think', text: '第一段分析' },
      { segmentId: 'content-2', afterStepId: 'tool-exec-1', text: '工具结果说明' },
      { segmentId: 'content-3', afterStepId: 'think-2', text: '第二段分析' },
    ]
    const visible = new Set(steps.map(s => s.id))
    const opts = { live: false, lastBlockIndex: 2 }
    const index = buildContentRowsIndex(steps, visible, blocks, opts)

    for (const s of steps) {
      expect(index.byStep.get(s.id) ?? []).toEqual(
        contentRowsAfterStep(s.id, steps, visible, blocks, opts),
      )
    }
    expect(index.orphan).toEqual([])
  })

  it('隐藏锚点前移到最近可见步，与旧逻辑一致', () => {
    // tool-exec-2 之后新增隐藏步，正文锚定其前一个可见步
    const withHidden = [...steps, step({ id: 'generate', phase: 'generate' })]
    const blocks = [{ segmentId: 'content-4', afterStepId: 'generate', text: '收尾' }]
    const visible = new Set(withHidden.filter(s => s.id !== 'generate').map(s => s.id))
    const opts = { live: false, lastBlockIndex: 0 }
    const index = buildContentRowsIndex(withHidden, visible, blocks, opts)

    expect(index.byStep.get('tool-exec-2')?.map(r => r.text)).toEqual(['收尾'])
    expect(contentRowsAfterStep('tool-exec-2', withHidden, visible, blocks, opts).map(r => r.text))
      .toEqual(['收尾'])
  })

  it('孤立段进 orphan，且 streaming 仅标记最后一段', () => {
    // intent 不可见（模拟隐藏步）且是首步、前方无可见步 → displayAnchor=null → orphan
    const blocks = [
      { segmentId: 'content-1', afterStepId: 'think', text: '可见段' },
      { segmentId: 'content-2', afterStepId: 'intent', text: '孤立段' },
    ]
    const visible = new Set(steps.filter(s => s.id !== 'intent').map(s => s.id))
    const opts = { live: true, lastBlockIndex: 1 }
    const index = buildContentRowsIndex(steps, visible, blocks, opts)

    expect(index.orphan.map(r => r.text)).toEqual(['孤立段'])
    expect(index.orphan[0].streaming).toBe(true)
    expect(orphanContentRows(steps, visible, blocks, opts).map(r => r.text)).toEqual(['孤立段'])
    expect(index.byStep.get('think')?.[0].streaming).toBe(false)
  })

  it('Plan DAG：仅 node-answer 块进入索引', () => {
    const dagSteps: ProcessingStep[] = [
      step({ id: 'plan', phase: 'plan', detail: 'planId=ep-1' }),
      step({ id: 'node-rag', phase: 'node', detail: '检索中' }),
      step({ id: 'node-answer', phase: 'node', result: '计划终稿' }),
    ]
    const blocks = [
      { segmentId: 'leak', afterStepId: 'node-rag', text: '抽屉摘要' },
      { segmentId: 'tail:node-answer', afterStepId: 'node-answer', text: '块' },
    ]
    const visible = new Set(dagSteps.map(s => s.id))
    const opts = { live: false, lastBlockIndex: 1 }
    const index = buildContentRowsIndex(dagSteps, visible, blocks, opts)

    expect(index.byStep.get('node-rag') ?? []).toEqual([])
    expect(index.byStep.get('node-answer')?.map(r => r.text)).toEqual(['计划终稿'])
  })

  it('空 blocks 返回空索引', () => {
    const visible = new Set(steps.map(s => s.id))
    const index = buildContentRowsIndex(steps, visible, undefined, { live: false, lastBlockIndex: -1 })
    expect(index.byStep.size).toBe(0)
    expect(index.orphan).toEqual([])
  })
})
