import { describe, expect, it } from 'vitest'
import { resolveLoopContinueRows } from './loopContinueDisplay'

describe('resolveLoopContinueRows', () => {
  it('only returns continue condition row', () => {
    const rows = resolveLoopContinueRows({
      'condition.left': '{{start.userQuery}}',
      'condition.op': 'contains',
      'condition.right': '继续',
      maxIterations: '2',
      onMaxIterations: 'exit',
    })
    expect(rows).toEqual([
      { key: 'continue', label: '继续循环', value: '{{start.userQuery}} 包含「继续」' },
    ])
  })
})
