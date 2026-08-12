import { describe, expect, it } from 'vitest'
import { resolvePreviewReadFailureDisplay } from './useSandboxPreviewTabs'

describe('resolvePreviewReadFailureDisplay', () => {
  it('shows empty preview and does not surface busy/error text', () => {
    const state = resolvePreviewReadFailureDisplay(
      new Error('系统繁忙，请稍后重试'),
    )
    expect(state.content).toBe('')
    expect(state.meta).toBe('')
    expect(state.shouldCache).toBe(false)
  })

  it('treats unknown failures the same: empty, no error chrome', () => {
    const state = resolvePreviewReadFailureDisplay('读取失败')
    expect(state.content).toBe('')
    expect(state.meta).toBe('')
    expect(state.shouldCache).toBe(false)
  })
})
