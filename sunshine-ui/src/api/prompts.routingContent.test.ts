import { describe, expect, it } from 'vitest'
import { parseRoutingContentJson, serializeRoutingContent } from './prompts'

describe('routing rule content · v6 语义（轨 A 绑技能/助手 / 轨 B 绑工作流）', () => {
  it('空内容默认快速/专业轨（mode=fast）', () => {
    const form = parseRoutingContentJson(null)
    expect(form.plan?.mode).toBe('fast')
    expect(form.matchType).toBe('regex')
  })

  it('旧 react 规则解析时回退 fast，技能绑定保留', () => {
    const form = parseRoutingContentJson(
      '{"matchType":"regex","match":"any","patterns":["制度怎么说"],'
      + '"plan":{"mode":"react","params":{"skill":"policy-qa"}}}',
    )
    expect(form.plan?.mode).toBe('fast')
    expect(form.plan?.params?.skill).toBe('policy-qa')
  })

  it('旧 plan-workflow 规则解析时回退 fast（轨 A）', () => {
    const form = parseRoutingContentJson(
      '{"matchType":"regex","patterns":["先.+再"],"plan":{"mode":"plan-workflow","params":{}}}',
    )
    expect(form.plan?.mode).toBe('fast')
  })

  it('工作流规则保持 workflow 轨与模板绑定', () => {
    const form = parseRoutingContentJson(
      '{"matchType":"regex","patterns":["待审批"],'
      + '"plan":{"mode":"workflow","workflowId":"finance-list","params":{"status":"pending"}}}',
    )
    expect(form.plan?.mode).toBe('workflow')
    expect(form.plan?.workflowId).toBe('finance-list')
    expect(form.plan?.params?.status).toBe('pending')
  })

  it('serialize 原样保留 mode 与绑定参数', () => {
    const json = serializeRoutingContent({
      matchType: 'regex',
      match: 'any',
      patterns: ['差旅办法'],
      domainGroups: {},
      minDomainGroups: 2,
      plan: { mode: 'fast', workflowId: null, params: { skill: 'travel-budget' } },
    })
    const parsed = JSON.parse(json)
    expect(parsed.plan.mode).toBe('fast')
    expect(parsed.plan.params.skill).toBe('travel-budget')
    expect(parsed.plan.workflowId).toBeNull()
  })

  it('serialize 助手绑定参数 agentIds 原样保留', () => {
    const json = serializeRoutingContent({
      matchType: 'regex',
      match: 'any',
      patterns: ['帮我分析'],
      domainGroups: {},
      minDomainGroups: 2,
      plan: { mode: 'fast', workflowId: null, params: { agentIds: 'finance-analyst' } },
    })
    const parsed = JSON.parse(json)
    expect(parsed.plan.mode).toBe('fast')
    expect(parsed.plan.params.agentIds).toBe('finance-analyst')
  })
})
