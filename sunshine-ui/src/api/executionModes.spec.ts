import { describe, expect, it } from 'vitest'
import {
  allowsAgentMention,
  allowsSkillMention,
  allowsWorkflowMention,
  EXECUTION_MODE_OPTIONS,
  isExecutionPreference,
  normalizeExecutionPreference,
} from './executionModes'

describe('executionModes · fast/pro/workflow', () => {
  it('normalize 仅认新 wire，未知/旧值回退 fast', () => {
    expect(normalizeExecutionPreference('plan-workflow')).toBe('fast')
    expect(normalizeExecutionPreference('auto')).toBe('fast')
    expect(normalizeExecutionPreference('react')).toBe('fast')
    expect(normalizeExecutionPreference('fast')).toBe('fast')
    expect(normalizeExecutionPreference('pro')).toBe('pro')
    expect(normalizeExecutionPreference('workflow')).toBe('workflow')
    expect(normalizeExecutionPreference(undefined)).toBe('fast')
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

  it('选项仅三项且 isExecutionPreference 拒旧值', () => {
    expect(EXECUTION_MODE_OPTIONS.map(o => o.value)).toEqual(['fast', 'pro', 'workflow'])
    expect(isExecutionPreference('fast')).toBe(true)
    expect(isExecutionPreference('auto')).toBe(false)
    expect(isExecutionPreference('react')).toBe(false)
    expect(isExecutionPreference('plan-workflow')).toBe(false)
  })
})
