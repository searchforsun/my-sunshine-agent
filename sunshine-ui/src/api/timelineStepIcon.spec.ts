import { describe, expect, it } from 'vitest'
import { resolveTimelineStepKind } from './timelineStepIcon'
import type { ProcessingStep } from './processingSteps'

function step(partial: Partial<ProcessingStep>): ProcessingStep {
  return { id: 'x', phase: 'think', lifecycle: 'done', ...partial }
}

describe('resolveTimelineStepKind', () => {
  it('decision / subagent 优先', () => {
    expect(resolveTimelineStepKind(step({ id: 'decision-1', phase: 'decision' }))).toBe('decision')
    expect(resolveTimelineStepKind(step({ id: 'subagent-1', phase: 'subagent' }))).toBe('subagent')
  })

  it('decision / subagent 仅 phase 命中（id 无前缀）', () => {
    expect(resolveTimelineStepKind(step({ id: 'p1', phase: 'decision' }))).toBe('decision')
    expect(resolveTimelineStepKind(step({ id: 'p2', phase: 'subagent' }))).toBe('subagent')
  })

  it('优先级冲突：id 含 decision- 前缀但 phase=tool 仍判 decision', () => {
    expect(resolveTimelineStepKind(step({ id: 'decision-1', phase: 'tool' }))).toBe('decision')
  })

  it('worker / harness plan', () => {
    expect(resolveTimelineStepKind(step({ id: 'worker-1', phase: 'worker' }))).toBe('worker')
    expect(resolveTimelineStepKind(step({ id: 'plan', phase: 'plan' }))).toBe('plan')
    expect(resolveTimelineStepKind(step({ id: 'plan-R2', phase: 'plan' }))).toBe('plan')
  })

  it('综合回答（planner-answer / phase=answer）', () => {
    expect(resolveTimelineStepKind(step({ id: 'planner-answer', phase: 'answer' }))).toBe('answer')
    expect(resolveTimelineStepKind(step({ id: 'p1', phase: 'answer' }))).toBe('answer')
  })

  it('外部智能体（external）', () => {
    expect(resolveTimelineStepKind(step({ id: 'external-oa-agent', phase: 'external' }))).toBe('external')
    expect(resolveTimelineStepKind(step({ id: 'e1', phase: 'external' }))).toBe('external')
  })

  it('业务节点（node-*，含 loop 轮次 i{n}-node-*）', () => {
    expect(resolveTimelineStepKind(step({ id: 'node-approve', phase: 'node' }))).toBe('node')
    expect(resolveTimelineStepKind(step({ id: 'i2-node-approve', phase: 'node' }))).toBe('node')
    // node-answer 归综合回答
    expect(resolveTimelineStepKind(step({ id: 'node-answer', phase: 'node' }))).toBe('answer')
  })

  it('rag / intent / skill / tasks / think', () => {
    expect(resolveTimelineStepKind(step({ id: 'rag', phase: 'tool' }))).toBe('rag')
    expect(resolveTimelineStepKind(step({ id: 'rag@1699999999999', phase: 'tool' }))).toBe('rag')
    expect(resolveTimelineStepKind(step({ id: 'i1', phase: 'intent' }))).toBe('intent')
    expect(resolveTimelineStepKind(step({ id: 's1', phase: 'skill' }))).toBe('skill')
    expect(resolveTimelineStepKind(step({ id: 't1', phase: 'tasks' }))).toBe('tasks')
    expect(resolveTimelineStepKind(step({ id: 'think-2', phase: 'think' }))).toBe('think')
  })

  it('工具步按 sandbox 细分', () => {
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__glob@1', phase: 'tool' }))).toBe('tool-search')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__read@1', phase: 'tool' }))).toBe('tool-view')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__edit@2', phase: 'tool' }))).toBe('tool-edit')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__webfetch@3', phase: 'tool' }))).toBe('tool-fetch')
    expect(resolveTimelineStepKind(step({ id: 'tool-sandbox__exec@4', phase: 'tool' }))).toBe('tool-exec')
    expect(resolveTimelineStepKind(step({ id: 'tool-doc-search@5', phase: 'tool' }))).toBe('tool')
  })

  it('其余兜底 generic', () => {
    expect(resolveTimelineStepKind(step({ id: 'i9', phase: 'loop' }))).toBe('generic')
    expect(resolveTimelineStepKind(step({ id: 'x', phase: 'generate' }))).toBe('generic')
    // 负向：id 含 rag 前缀但非精确 rag 步，不误判为检索
    expect(resolveTimelineStepKind(step({ id: 'rag-1', phase: 'think' }))).toBe('generic')
    expect(resolveTimelineStepKind(step({ id: 'rag-1', phase: 'tool' }))).toBe('generic')
  })
})
