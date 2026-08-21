import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import {
  hasExpandableContent,
  resolveStepExpandInner,
  resolveStepExpandLead,
  resolveStepExpandPanels,
  resolveStepHeaderText,
  resolveStepSummaryFull,
} from './processingStepsDisplay'

/**
 * harness worker 行（v17.4）：正文由嵌套 OperationStack 的 contentBlocks 承载，
 * OperationCard 展开区与折叠主行预览均不得渲染 result（避免双份正文/折叠透露正文）。
 */
describe('worker step expand panels', () => {
  const workerStep: ProcessingStep = {
    id: 'worker-t1',
    phase: 'worker',
    lifecycle: 'done',
    label: '分析待办',
    result: '完整终稿正文',
    subSteps: [{ id: 'think', phase: 'think', lifecycle: 'done', reasoning: '…' }],
    contentBlocks: [{ segmentId: 'content-1', afterStepId: 'think', text: '流式正文' }],
  }

  it('expandInner/Lead/panels 均不承载 result 正文', () => {
    expect(resolveStepExpandInner(workerStep)).toBe('')
    expect(resolveStepExpandLead(workerStep)).toBe('')
    expect(resolveStepExpandPanels(workerStep)).toEqual({ lead: '', body: '' })
  })

  it('折叠主行预览不回退 result（不透露正文）', () => {
    expect(resolveStepSummaryFull(workerStep)).toBe('')
    expect(resolveStepHeaderText(workerStep)).toBe('')
  })

  it('有 subSteps/contentBlocks 仍可展开（嵌套 stack 承载正文）', () => {
    expect(hasExpandableContent(workerStep)).toBe(true)
  })

  it('无嵌套内容的 worker 骨架不可展开', () => {
    const bare: ProcessingStep = { id: 'worker-t2', phase: 'worker', lifecycle: 'running' }
    expect(hasExpandableContent(bare)).toBe(false)
  })
})
