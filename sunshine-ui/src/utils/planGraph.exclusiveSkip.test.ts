import { describe, expect, it } from 'vitest'
import type { PlanGraph } from '../api/executionPlans'
import type { ProcessingStep } from '../api/processingSteps'
import { buildDagNodes } from './planGraph'

describe('buildDagNodes exclusive skip', () => {
  it('marks untaken exclusive arms as skipped when another arm completed', () => {
    const graph: PlanGraph = {
      nodes: [
        { id: 'xg', type: 'exclusive-gateway', displayName: '条件分支', params: {} },
        { id: 'rag-hr', type: 'rag', displayName: '人事制度检索', params: {} },
        { id: 'rag-fin', type: 'rag', displayName: '财务制度检索', params: {} },
        { id: 'rag-safe', type: 'rag', displayName: '安全制度检索', params: {} },
        { id: 'answer', type: 'answer', displayName: '回答', params: {} },
      ],
      edges: [
        { from: 'start', to: 'xg' },
        { from: 'xg', to: 'rag-hr' },
        { from: 'xg', to: 'rag-fin' },
        { from: 'xg', to: 'rag-safe' },
        { from: 'rag-hr', to: 'answer' },
        { from: 'rag-fin', to: 'answer' },
        { from: 'rag-safe', to: 'answer' },
      ],
    }
    const steps: ProcessingStep[] = [
      {
        id: 'node-rag-fin',
        phase: 'node',
        lifecycle: 'done',
        summary: { after: '财务制度检索完成' },
        startedAt: 1,
        endedAt: 2001,
        label: '财务制度检索',
      },
      {
        id: 'node-answer',
        phase: 'node',
        lifecycle: 'done',
        summary: { after: '回答完成' },
        startedAt: 2001,
        endedAt: 6001,
        label: '回答',
      },
    ]
    const nodes = buildDagNodes(graph, steps)
    const byId = Object.fromEntries(nodes.map(n => [n.id, n.status]))
    expect(byId.xg).toBe('done')
    expect(byId['rag-fin']).toBe('done')
    expect(byId['rag-hr']).toBe('skipped')
    expect(byId['rag-safe']).toBe('skipped')
    expect(byId.answer).toBe('done')
  })
})
