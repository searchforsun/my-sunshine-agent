// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { isElVisibleInRoot } from './floatingTaskboard'

function makeBox(top: number, height: number): HTMLElement {
  const el = document.createElement('div')
  const bottom = top + height
  vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
    top, bottom, left: 0, right: 200, width: 200, height,
    x: 0, y: top, toJSON: () => ({}),
  } as DOMRect)
  return el
}

describe('isElVisibleInRoot', () => {
  let root: HTMLElement
  beforeEach(() => {
    root = makeBox(0, 600)
  })

  it('visible when element intersects the scroll viewport', () => {
    const el = makeBox(200, 40)
    expect(isElVisibleInRoot(el, root)).toBe(true)
  })

  it('visible when element overlaps the viewport top edge', () => {
    const el = makeBox(-20, 40)
    expect(isElVisibleInRoot(el, root)).toBe(true)
  })

  it('invisible when element fully scrolled above the viewport', () => {
    const el = makeBox(-100, 40)
    expect(isElVisibleInRoot(el, root)).toBe(false)
  })

  it('invisible when element fully below the viewport', () => {
    const el = makeBox(700, 40)
    expect(isElVisibleInRoot(el, root)).toBe(false)
  })
})
