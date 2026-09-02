import { describe, expect, it } from 'vitest'
import { normalizeStep } from './processingStepsParse'
import {
  resolveIntentRoutingTraces,
  type ProcessingStep,
} from './processingSteps'

function intentStep(overrides: Partial<ProcessingStep> = {}): ProcessingStep {
  return normalizeStep({
    id: 'intent',
    phase: 'intent',
    lifecycle: 'done',
    summary: { after: '将按您指定的「快速」模式处理' },
    ...overrides,
  }) as ProcessingStep
}

describe('routingTraces 解析', () => {
  it('metadata.routingTraces 原样解析进 StepMetadata', () => {
    const step = intentStep({
      metadata: {
        routingTraces: [
          { layer: 'mode', label: '模式锁定', detail: 'pro（专业）' },
          { layer: 'track', label: '轨道', detail: '轨 A：skill + agent' },
          { layer: 'L0', label: '/Skill', detail: 'skill=oa 报销' },
          { layer: 'final', label: '绑定结果', detail: 'skill=oa 报销' },
        ],
      },
    })
    expect(step.metadata?.routingTraces).toHaveLength(4)
    expect(step.metadata?.routingTraces?.[0]).toEqual({
      layer: 'mode',
      label: '模式锁定',
      detail: 'pro（专业）',
    })
  })

  it('空对象行被过滤；空数组→ undefined', () => {
    const step = intentStep({
      metadata: {
        routingTraces: [{ layer: '', label: '', detail: '' }],
      },
    })
    expect(step.metadata?.routingTraces).toBeUndefined()
  })

  it('wire 无 routingTraces 时元数据不含该字段', () => {
    const step = intentStep({ metadata: { routingReason: 'user:forced-fast' } })
    expect(step.metadata?.routingTraces).toBeUndefined()
  })
})

describe('resolveIntentRoutingTraces', () => {
  it('intent 步返回完整 traces', () => {
    const step = intentStep({
      metadata: {
        routingTraces: [
          { layer: 'mode', label: '模式锁定', detail: 'fast（快速）' },
          { layer: 'final', label: '绑定结果', detail: 'fast 自主分析（未绑定）' },
        ],
      },
    })
    expect(resolveIntentRoutingTraces(step)).toHaveLength(2)
  })

  it('老消息返回空数组', () => {
    const step = intentStep({ metadata: { routingReason: 'user:forced-fast' } })
    expect(resolveIntentRoutingTraces(step)).toEqual([])
  })
})
