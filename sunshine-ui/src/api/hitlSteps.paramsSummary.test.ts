import { describe, expect, it } from 'vitest'
import { formatHitlParamsSummary, parseHitlParamsSummary } from './hitlSteps'

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
