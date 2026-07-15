import { describe, expect, it } from 'vitest'
import type { PlanGraph } from '../api/executionPlans'
import type { ProcessingStep } from '../api/processingSteps'
import { buildDagNodes } from './planGraph'

describe('buildDagNodes loop body status', () => {
  const graph: PlanGraph = {
    nodes: [
      { id: 'loop-1', type: 'loop', displayName: '条件循环', params: {} },
      { id: 'rag-1', type: 'rag', parentId: 'loop-1', displayName: '知识检索', params: {} },
      { id: 'tool-1', type: 'tool', parentId: 'loop-1', displayName: '查询待办财务', params: {} },
      { id: 'answer', type: 'answer', displayName: '回答', params: {} },
    ],
    edges: [
      { from: 'start', to: 'loop-1' },
      { from: 'rag-1', to: 'tool-1' },
      { from: 'loop-1', to: 'answer' },
    ],
  }

  it('reads body status from loop.subSteps while parent running', () => {
    const steps: ProcessingStep[] = [
      {
        id: 'node-loop-1',
        phase: 'node',
        lifecycle: 'running',
        label: '条件循环',
        summary: { active: '条件循环' },
        subSteps: [
          {
            id: 'i1-node-rag-1',
            phase: 'node',
            lifecycle: 'done',
            label: '知识检索',
            startedAt: 1,
            endedAt: 501,
          },
          {
            id: 'i1-node-tool-1',
            phase: 'node',
            lifecycle: 'running',
            label: '查询待办财务',
            startedAt: 501,
          },
        ],
      },
    ]
    const byId = Object.fromEntries(buildDagNodes(graph, steps).map(n => [n.id, n.status]))
    expect(byId['loop-1']).toBe('running')
    expect(byId['rag-1']).toBe('done')
    expect(byId['tool-1']).toBe('running')
    expect(byId.answer).toBe('pending')
  })

  it('does not mark body skipped when parent done but shell completed early without subSteps yet is avoided by running parent', () => {
    const steps: ProcessingStep[] = [
      {
        id: 'node-loop-1',
        phase: 'node',
        lifecycle: 'running',
        label: '条件循环',
        summary: { active: '条件循环' },
      },
    ]
    const byId = Object.fromEntries(buildDagNodes(graph, steps).map(n => [n.id, n.status]))
    expect(byId['rag-1']).toBe('pending')
    expect(byId['tool-1']).toBe('pending')
  })

  it('marks body skipped only when parent terminal and no subSteps', () => {
    const steps: ProcessingStep[] = [
      {
        id: 'node-loop-1',
        phase: 'node',
        lifecycle: 'done',
        label: '条件循环',
        summary: { after: '条件循环完成（0 轮）' },
        startedAt: 1,
        endedAt: 3,
      },
      {
        id: 'node-answer',
        phase: 'node',
        lifecycle: 'done',
        label: '回答',
        startedAt: 3,
        endedAt: 10,
      },
    ]
    const byId = Object.fromEntries(buildDagNodes(graph, steps).map(n => [n.id, n.status]))
    expect(byId['rag-1']).toBe('skipped')
    expect(byId['tool-1']).toBe('skipped')
  })
})
