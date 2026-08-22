import { describe, expect, it } from 'vitest'
import type { TaskBoardItemView } from './processingSteps'
import { hasSecondaryTodos } from './taskBoardWaves'

function item(
  id: string,
  opts?: { dependsOn?: string[]; secondary?: TaskBoardItemView[] },
): TaskBoardItemView {
  return {
    id,
    content: id,
    status: 'pending',
    dependsOn: opts?.dependsOn,
    secondary: opts?.secondary,
  }
}

describe('hasSecondaryTodos', () => {
  it('is false when secondary missing or empty', () => {
    expect(hasSecondaryTodos(item('a'))).toBe(false)
    expect(hasSecondaryTodos(item('a', { secondary: [] }))).toBe(false)
  })

  it('is true when secondary has items', () => {
    expect(hasSecondaryTodos(item('a', {
      secondary: [{ id: 's1', content: 'todo', status: 'pending' }],
    }))).toBe(true)
  })
})
