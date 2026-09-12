import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import { mergeRestoredMessages } from './conversationCache'

function msg(id: string, role: 'user' | 'assistant', content: string, seq?: number): ChatMessage {
  return { id, role, content, seq }
}

function tmsg(id: string, role: 'user' | 'assistant', content: string, atMs: number, seq?: number, status?: string): ChatMessage {
  return {
    id, role, content, seq,
    status: status as ChatMessage['status'],
    timelineStartedAt: atMs,
    createdAt: atMs,
  } as ChatMessage
}

describe('mergeRestoredMessages', () => {
  it('保留后端尚未落库的最新缓存消息（seq 缺失时不得丢弃）', () => {
    // 刷新窗口：后端 commitFinal 尚未完成，API 只返回旧窗口（seq 1-4），
    // 本地缓存含最新两条流式消息（SSE 只赋 id、不赋 seq）
    const api = [
      msg('a1', 'user', '旧问题', 1),
      msg('a2', 'assistant', '旧回答', 2),
      msg('a3', 'user', '中间问题', 3),
      msg('a4', 'assistant', '中间回答', 4),
    ]
    const cached = [
      ...api,
      msg('b1', 'user', '新问题'),
      msg('b2', 'assistant', '新回答'),
    ]
    const merged = mergeRestoredMessages(api, cached)
    expect(merged).toHaveLength(6)
    expect(merged[4].content).toBe('新问题')
    expect(merged[5].content).toBe('新回答')
    expect(merged[merged.length - 1].role).toBe('assistant')
  })

  it('分页窗口合并时不把窗口外的更早消息重复追加', () => {
    // API 只返回最近窗口（seq 5-7），缓存全量（seq 1-7）：seq 1-4 不得被追加
    const windowApi = [
      msg('m5', 'assistant', '第五', 5),
      msg('m6', 'user', '第六', 6),
      msg('m7', 'assistant', '第七', 7),
    ]
    const cached = [
      msg('m1', 'user', '一', 1),
      msg('m2', 'assistant', '二', 2),
      msg('m3', 'user', '三', 3),
      msg('m4', 'assistant', '四', 4),
      ...windowApi,
    ]
    const merged = mergeRestoredMessages(windowApi, cached)
    expect(merged).toHaveLength(3)
    expect(merged.map(m => m.id)).toEqual(['m5', 'm6', 'm7'])
  })

  it('缓存存在 API 未返回的更高 seq 消息时按 seq 增量追加尾部', () => {
    const api = [
      msg('m1', 'user', '一', 1),
      msg('m2', 'assistant', '二', 2),
    ]
    const cached = [
      ...api,
      msg('m3', 'user', '三', 3),
      msg('m4', 'assistant', '四', 4),
    ]
    const merged = mergeRestoredMessages(api, cached)
    expect(merged).toHaveLength(4)
    expect(merged[2].content).toBe('三')
    expect(merged[3].content).toBe('四')
  })

  it('同 id 消息按更长正文合并', () => {
    const api = [msg('m2', 'assistant', '回答', 2)]
    const cached = [msg('m2', 'assistant', '更长的回答内容', 2)]
    const merged = mergeRestoredMessages(api, cached)
    expect(merged).toHaveLength(1)
    expect(merged[0].content).toBe('更长的回答内容')
  })

  it('缓存为空时直接返回 API 消息', () => {
    const api = [msg('m1', 'user', '一', 1)]
    const merged = mergeRestoredMessages(api, null)
    expect(merged).toEqual(api)
  })

  it('中断轮次按发送时间戳归位，不被挤到后续已完成轮次之后', () => {
    // API 仅返回最近窗口 C3,C4（时间 3000/4000）；缓存含发生在时间轴中间的中断回答 B（2500，无 seq）。
    // 重构后每条消息都有发送时间戳，排序以时间戳为唯一权威，B 应排在 C 之前。
    const api = [
      tmsg('c3', 'user', '问题C', 3000, 3),
      tmsg('c4', 'assistant', '回答C', 4000, 4),
    ]
    const cached = [
      tmsg('a1', 'user', '问题A', 1000, 1),
      tmsg('a2', 'assistant', '回答A', 2000, 2),
      tmsg('b_interrupt', 'assistant', '回答B(中断)', 2500, undefined, 'interrupted'),
      ...api,
    ]
    const merged = mergeRestoredMessages(api, cached)
    const ids = merged.map(m => m.id)
    expect(ids.indexOf('b_interrupt')).toBeLessThan(ids.indexOf('c3'))
    const times = merged.map(m => m.timelineStartedAt as number)
    for (let i = 1; i < times.length; i++) {
      expect(times[i]).toBeGreaterThanOrEqual(times[i - 1])
    }
  })

  it('最新未落库流式消息（时间戳最晚）排尾部', () => {
    const api = [tmsg('a1', 'user', '问题1', 1000, 1), tmsg('a2', 'assistant', '回答1', 2000, 2)]
    const cached = [
      ...api,
      tmsg('b1', 'user', '新问题', 3000),
      tmsg('b2', 'assistant', '新回答', 4000),
    ]
    const merged = mergeRestoredMessages(api, cached)
    expect(merged.map(m => m.id)).toEqual(['a1', 'a2', 'b1', 'b2'])
  })
})
