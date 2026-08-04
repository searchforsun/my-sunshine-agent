import { describe, expect, it } from 'vitest'
import { formatHitlParamsSummary, isToolStepId, parseHitlParamsSummary } from './hitlSteps'

describe('HITL params summary hides body fields', () => {
  it('parseHitlParamsSummary skips content / old_string / new_string / command', () => {
    const pairs = parseHitlParamsSummary(
      'path=/workspace/sample.csv, content=id,amount A001,150.50, old_string=a, new_string=b, command=python3 -c "x"',
    )
    expect(pairs.map(p => p.key)).toEqual(['path'])
    expect(pairs[0].value).toBe('/workspace/sample.csv')
  })

  it('formatHitlParamsSummary omits body keys', () => {
    expect(formatHitlParamsSummary(
      'path=/workspace/x.txt, content=hello world',
      48,
    )).toBe('path=/workspace/x.txt')
  })
})

describe('isToolStepId 识别工具步（与后端 ToolStepIds.isToolStep 对齐）', () => {
  it('tool-* 前缀步命中', () => {
    expect(isToolStepId('tool-sdk__sunshine-oa__list_oa_tasks@1718750000123')).toBe(true)
    expect(isToolStepId('tool@2')).toBe(true)
  })

  it('rag 检索知识库步命中（含 @epochMs 后缀）', () => {
    expect(isToolStepId('rag')).toBe(true)
    expect(isToolStepId('rag@1718750000123')).toBe(true)
  })

  it('非工具步不命中', () => {
    expect(isToolStepId('intent')).toBe(false)
    expect(isToolStepId('think-2')).toBe(false)
    expect(isToolStepId('node-answer')).toBe(false)
  })
})
