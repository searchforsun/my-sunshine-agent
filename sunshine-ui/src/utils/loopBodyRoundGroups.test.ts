import { describe, expect, it } from 'vitest'
import { groupLoopBodySubStepsByRound } from './loopBodyRoundGroups'
import type { ProcessingStep } from '../api/processingSteps'

function step(id: string, label: string): ProcessingStep {
  return {
    id,
    phase: 'node',
    lifecycle: 'done',
    label,
    summary: { after: label },
  }
}

describe('groupLoopBodySubStepsByRound', () => {
  it('groups i{n}- steps by round', () => {
    const groups = groupLoopBodySubStepsByRound([
      step('i1-node-rag', '知识检索'),
      step('i1-node-tool', '查询待办'),
      step('i2-node-rag', '知识检索'),
      step('i2-node-tool', '查询待办'),
    ])
    expect(groups).toHaveLength(2)
    expect(groups[0]).toEqual({
      round: 1,
      steps: [expect.objectContaining({ id: 'i1-node-rag' }), expect.objectContaining({ id: 'i1-node-tool' })],
    })
    expect(groups[1].round).toBe(2)
    expect(groups[1].steps.map(s => s.id)).toEqual(['i2-node-rag', 'i2-node-tool'])
  })

  it('keeps unprefixed steps in round 0', () => {
    const groups = groupLoopBodySubStepsByRound([
      step('think', '思考'),
      step('i1-node-rag', '检索'),
    ])
    expect(groups.map(g => g.round)).toEqual([0, 1])
  })
})
