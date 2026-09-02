import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { contentRowsAfterStep } from './contentInterleave'

/** 展开态 toolGroup/roundGroup 须对组内每个 stepId 调用 contentRowsAfterStep，否则终稿丢失 */
describe('contentRowsAfterStep · 工具步锚定终稿', () => {
  const steps: ProcessingStep[] = [
    { id: 'intent', phase: 'intent', lifecycle: 'done' },
    { id: 'think', phase: 'think', lifecycle: 'done', stepSummary: '先读代码' },
    { id: 'tool-read-1', phase: 'tool', lifecycle: 'done' },
    { id: 'tool-exec-1', phase: 'tool', lifecycle: 'done' },
  ]
  const visible = new Set(steps.map(s => s.id))
  const blocks = [
    { segmentId: 'mid', afterStepId: 'think', text: '过程说明' },
    { segmentId: 'final', afterStepId: 'tool-exec-1', text: '## P15 推进报告' },
  ]
  const opts = { live: false, lastBlockIndex: 1 }

  it('think 锚点可取出中间正文', () => {
    expect(contentRowsAfterStep('think', steps, visible, blocks, opts).map(r => r.text))
      .toEqual(['过程说明'])
  })

  it('末工具锚点可取出终稿（展开态须在 toolGroup/roundGroup 后挂载）', () => {
    expect(contentRowsAfterStep('tool-exec-1', steps, visible, blocks, opts).map(r => r.text))
      .toEqual(['## P15 推进报告'])
  })

  it('组内多工具按 id 汇总时可拼出完整穿插', () => {
    const hostIds = ['tool-read-1', 'tool-exec-1']
    const texts = hostIds.flatMap(id =>
      contentRowsAfterStep(id, steps, visible, blocks, opts).map(r => r.text),
    )
    expect(texts).toEqual(['## P15 推进报告'])
  })
})
