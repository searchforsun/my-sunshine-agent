// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage } from './chat'
import { cacheMessages, loadCachedMessages, loadCachedIndex, MAX_CACHE_BYTES } from './conversationCache'

function bigMsg(id: string, kbSize: number): ChatMessage {
  // steps 塞充体积：模拟 task 会话单条消息 steps 达百 KB 级的真实分布
  return { id, role: 'assistant', content: 'x', steps: [{ id: `step-${id}`, title: 'y'.repeat(kbSize * 1024) }] } as unknown as ChatMessage
}

describe('cacheMessages 体积上限', () => {
  beforeEach(() => {
    localStorage.clear()
  })
  afterEach(() => {
    localStorage.clear()
  })

  it('未超限时不裁剪', () => {
    const msgs = [bigMsg('m1', 1), bigMsg('m2', 1)]
    cacheMessages('c1', msgs)
    expect(loadCachedMessages('c1')).toHaveLength(2)
  })

  it('超限时从最旧消息开始裁剪，至少保留最新一条', () => {
    const msgs = Array.from({ length: 30 }, (_, i) => bigMsg(`m${i}`, 512))
    cacheMessages('c1', msgs)
    const cached = loadCachedMessages('c1')!
    expect(cached.length).toBeLessThan(msgs.length)
    // 最旧的 m0 被裁掉，最新一条保留
    expect(cached.some(m => m.id === 'm0')).toBe(false)
    expect(cached[cached.length - 1].id).toBe(`m${msgs.length - 1}`)
    const bytes = new TextEncoder().encode(JSON.stringify(cached)).length
    expect(bytes).toBeLessThanOrEqual(MAX_CACHE_BYTES)
  })

  it('单条即超限时保留最新一条', () => {
    const msgs = [bigMsg('old', 512), bigMsg('huge', 6 * 1024)]
    cacheMessages('c1', msgs)
    const cached = loadCachedMessages('c1')!
    expect(cached).toHaveLength(1)
    expect(cached[0].id).toBe('huge')
  })

  it('站点配额耗尽时淘汰最旧会话腾位后写入成功', () => {
    // 模拟受限配额：先写入两个旧会话占满，再写第三个会话触发淘汰
    const quota = new Map<string, string>()
    const stub = {
      getItem: (k: string) => quota.get(k) ?? null,
      setItem: (k: string, v: string) => {
        const total = [...quota.values()].reduce((s, x) => s + x.length, 0)
        if (total + v.length > 9 * 1024 * 1024) throw new DOMException('QuotaExceededError')
        quota.set(k, v)
      },
      removeItem: (k: string) => { quota.delete(k) },
      clear: () => quota.clear(),
      key: (i: number) => [...quota.keys()][i] ?? null,
      get length() { return quota.size },
    }
    vi.stubGlobal('localStorage', stub)
    try {
      cacheMessages('old-1', [bigMsg('a', 3 * 1024)], { title: '旧1', createdAt: 1, updatedAt: 1 })
      cacheMessages('old-2', [bigMsg('b', 3 * 1024)], { title: '旧2', createdAt: 2, updatedAt: 2 })
      // 写入 ~5MB 会话：上限裁剪到 4MB 后仍超模拟配额 → 淘汰最旧的 old-1 腾位
      cacheMessages('new', [bigMsg('c', 5 * 1024)], { title: '新', createdAt: 3, updatedAt: 3 })
      expect(quota.has('sunshine-conv-msgs:old-1')).toBe(false)
      expect(loadCachedMessages('new')).toBeDefined()
      expect(loadCachedIndex().some(c => c.id === 'new')).toBe(true)
    } finally {
      vi.unstubAllGlobals()
    }
  })
})
