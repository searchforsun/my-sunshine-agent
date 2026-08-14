import { beforeEach, describe, expect, it, vi } from 'vitest'

function createLocalStorageStub() {
  const store = new Map<string, string>()
  return {
    getItem: vi.fn((k: string) => store.get(k) ?? null),
    setItem: vi.fn((k: string, v: string) => { store.set(k, v) }),
    removeItem: vi.fn((k: string) => { store.delete(k) }),
    clear: vi.fn(() => { store.clear() }),
    key: vi.fn((i: number) => [...store.keys()][i] ?? null),
    get length() { return store.size },
  }
}

beforeEach(() => {
  vi.resetModules()
  vi.stubGlobal('localStorage', createLocalStorageStub())
})

describe('useTimelineStyle', () => {
  it('无存储时默认 minimal', async () => {
    const { useTimelineStyle } = await import('./useTimelineStyle')
    expect(useTimelineStyle().timelineStyle.value).toBe('minimal')
  })

  it('非法存储值回落 minimal', async () => {
    localStorage.setItem('sunshine.timeline.style', 'fancy')
    const { useTimelineStyle } = await import('./useTimelineStyle')
    expect(useTimelineStyle().timelineStyle.value).toBe('minimal')
  })

  it('setTimelineStyle 写 ref 与 localStorage', async () => {
    const { useTimelineStyle } = await import('./useTimelineStyle')
    const { timelineStyle, setTimelineStyle } = useTimelineStyle()
    setTimelineStyle('standard')
    expect(timelineStyle.value).toBe('standard')
    expect(localStorage.getItem('sunshine.timeline.style')).toBe('standard')
  })
})
