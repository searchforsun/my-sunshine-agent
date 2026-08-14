// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import {
  clearMermaidSvgCache,
  getCachedMermaidSvg,
  getMermaidError,
  loadMermaidSvg,
  setMermaidError,
} from './mermaidSvgCache'

const fail = () => Promise.reject(new Error('Lexical error in diagram'))

describe('mermaidSvgCache failure state', () => {
  it('records formatted error on render failure so rebuild restores error state', async () => {
    clearMermaidSvgCache()
    const src = 'graph TD\nA-->B\nbad:'

    await expect(loadMermaidSvg(src, fail, () => 'svg-1')).rejects.toThrow('Lexical error')

    expect(getMermaidError(src)).toBe('Lexical error in diagram')
    expect(getCachedMermaidSvg(src)).toBeUndefined()
  })

  it('clears previous error once the same source renders successfully', async () => {
    clearMermaidSvgCache()
    const src = 'graph TD\nA-->B'
    setMermaidError(src, 'old failure')

    const svg = await loadMermaidSvg(src, async () => '<svg>ok</svg>', () => 'svg-2')

    expect(svg).toBe('<svg>ok</svg>')
    expect(getMermaidError(src)).toBeUndefined()
    expect(getCachedMermaidSvg(src)).toBe('<svg>ok</svg>')
  })

  it('clearMermaidSvgCache drops both svg and error state', () => {
    clearMermaidSvgCache()
    const src = 'graph TD\nA-->B'
    setMermaidError(src, 'detail')
    expect(getMermaidError(src)).toBe('detail')

    clearMermaidSvgCache()
    expect(getMermaidError(src)).toBeUndefined()
  })
})
