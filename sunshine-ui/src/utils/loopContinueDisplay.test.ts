import { describe, expect, it } from 'vitest'
import { resolveLoopContinueRows } from './loopContinueDisplay'

describe('resolveLoopContinueRows', () => {
  it('returns continue condition rows from conditions array', () => {
    const rows = resolveLoopContinueRows({
      conditions: [
        { left: '{{start.userQuery}}', op: 'contains', right: '继续' },
      ] as unknown as Record<string, string>,
      conditionLogic: 'and',
      maxIterations: '2',
      onMaxIterations: 'exit',
    })
    expect(rows).toEqual([
      { key: 'continue-0', label: '继续循环', value: '{{start.userQuery}} 包含「继续」' },
    ])
  })

  it('returns multiple rows with or logic', () => {
    const rows = resolveLoopContinueRows({
      conditions: [
        { left: '{{a.output}}', op: 'eq', right: '1' },
        { left: '{{b.output}}', op: 'gt', right: '2' },
      ] as unknown as Record<string, string>,
      conditionLogic: 'or',
    })
    expect(rows).toHaveLength(2)
    expect(rows[0].label).toBe('继续循环（任一）')
  })
})
