import { describe, expect, it } from 'vitest'
import {
  hasOpenMermaidFenceAtEnd,
  stripTrailingOpenMermaidFence,
} from './mermaidFence'

describe('mermaidFence', () => {
  it('detects open fence at end', () => {
    const open = '## t\n```mermaid\ngraph TD\nA-->B'
    expect(hasOpenMermaidFenceAtEnd(open)).toBe(true)
    const closed = open + '\n```\nmore text'
    expect(hasOpenMermaidFenceAtEnd(closed)).toBe(false)
  })

  it('strips trailing open fence for stable streaming render', () => {
    const open = '## t\n\n```mermaid\ngraph TD\nA-->B'
    expect(stripTrailingOpenMermaidFence(open)).toBe('## t')
    const closed = open + '\n```\n## next'
    expect(stripTrailingOpenMermaidFence(closed)).toBe(closed)
  })
})
