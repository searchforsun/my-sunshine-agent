import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import { hasExpandableContent } from './processingStepsDisplay'

describe('hasExpandableContent loop body agent', () => {
  it('treats i*-node with nested think/content as expandable', () => {
    const step: ProcessingStep = {
      id: 'i1-node-agent-1',
      phase: 'node',
      lifecycle: 'done',
      label: '综合分析',
      subSteps: [{ id: 'think', phase: 'think', lifecycle: 'done' }],
      contentBlocks: [{ segmentId: 'c1', afterStepId: 'think', text: '正文' }],
    }
    expect(hasExpandableContent(step)).toBe(true)
  })
})
