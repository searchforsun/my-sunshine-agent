import { describe, expect, it } from 'vitest'
import {
  allowsAgentMention,
  allowsSkillMention,
  allowsWorkflowMention,
  EXECUTION_MODE_OPTIONS,
  isExecutionMode,
  normalizeExecutionMode,
} from './executionModes'

describe('executionModes · fast/pro/workflow', () => {
  it('normalize 仅认新 wire，未知/旧值回退 fast', () => {
    expect(normalizeExecutionMode('plan-workflow')).toBe('fast')
    expect(normalizeExecutionMode('auto')).toBe('fast')
    expect(normalizeExecutionMode('react')).toBe('fast')
    expect(normalizeExecutionMode('fast')).toBe('fast')
    expect(normalizeExecutionMode('pro')).toBe('pro')
    expect(normalizeExecutionMode('workflow')).toBe('workflow')
    expect(normalizeExecutionMode(undefined)).toBe('fast')
  })

  it('mention 门控：fast/pro 开 skill+agent，仅 workflow 开 #', () => {
    expect(allowsWorkflowMention('fast')).toBe(false)
    expect(allowsWorkflowMention('pro')).toBe(false)
    expect(allowsWorkflowMention('workflow')).toBe(true)
    expect(allowsAgentMention('pro')).toBe(true)
    expect(allowsAgentMention('fast')).toBe(true)
    expect(allowsAgentMention('workflow')).toBe(false)
    expect(allowsSkillMention('fast')).toBe(true)
    expect(allowsSkillMention('pro')).toBe(true)
    expect(allowsSkillMention('workflow')).toBe(false)
  })

  it('选项仅三项且 isExecutionMode 拒旧值', () => {
    expect(EXECUTION_MODE_OPTIONS.map(o => o.value)).toEqual(['fast', 'pro', 'workflow'])
    expect(isExecutionMode('fast')).toBe(true)
    expect(isExecutionMode('auto')).toBe(false)
    expect(isExecutionMode('react')).toBe(false)
    expect(isExecutionMode('plan-workflow')).toBe(false)
  })
})
