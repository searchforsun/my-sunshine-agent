import { describe, expect, it } from 'vitest'
import type { TaskBoardItemView } from './processingSteps'
import { groupTaskBoardWaves, hasSecondaryTodos } from './taskBoardWaves'

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

describe('groupTaskBoardWaves', () => {
  it('puts independent items in one wave', () => {
    const waves = groupTaskBoardWaves([item('a'), item('b')])
    expect(waves).toHaveLength(1)
    expect(waves[0].map(t => t.id).sort()).toEqual(['a', 'b'])
  })

  it('groups serial dependsOn into successive waves', () => {
    const waves = groupTaskBoardWaves([
      item('a'),
      item('b', { dependsOn: ['a'] }),
      item('c', { dependsOn: ['b'] }),
    ])
    expect(waves.map(w => w.map(t => t.id))).toEqual([['a'], ['b'], ['c']])
  })

  it('keeps parallel siblings in the same wave', () => {
    const waves = groupTaskBoardWaves([
      item('root'),
      item('left', { dependsOn: ['root'] }),
      item('right', { dependsOn: ['root'] }),
      item('join', { dependsOn: ['left', 'right'] }),
    ])
    expect(waves[0].map(t => t.id)).toEqual(['root'])
    expect(waves[1].map(t => t.id).sort()).toEqual(['left', 'right'])
    expect(waves[2].map(t => t.id)).toEqual(['join'])
  })

  it('dumps cyclic remainder into a final wave', () => {
    const waves = groupTaskBoardWaves([
      item('a', { dependsOn: ['b'] }),
      item('b', { dependsOn: ['a'] }),
    ])
    expect(waves).toHaveLength(1)
    expect(waves[0].map(t => t.id).sort()).toEqual(['a', 'b'])
  })
})

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
