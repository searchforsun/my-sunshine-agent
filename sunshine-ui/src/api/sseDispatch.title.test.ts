import { describe, expect, it } from 'vitest'
import { parseSsePayload } from './sseDispatch'

describe('parseSsePayload title', () => {
  it('parses title meta with conversationId and title', () => {
    const parsed = parseSsePayload(
      JSON.stringify({ type: 'title', conversationId: 'conv-1', title: '排查订单支付失败' }),
    )
    expect(parsed.kind).toBe('meta')
    if (parsed.kind !== 'meta') return
    expect(parsed.meta.type).toBe('title')
    expect(parsed.meta.conversationId).toBe('conv-1')
    expect(parsed.meta.title).toBe('排查订单支付失败')
  })

  it('ignores title meta without title', () => {
    const parsed = parseSsePayload(JSON.stringify({ type: 'title', conversationId: 'conv-1' }))
    expect(parsed.kind).toBe('meta')
    if (parsed.kind !== 'meta') return
    expect(parsed.meta.title).toBeUndefined()
  })
})
