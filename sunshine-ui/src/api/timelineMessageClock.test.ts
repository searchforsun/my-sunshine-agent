import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import {
  hydrateTimelineBoundsFromMessageTimes,
  stampTimelineEnded,
  stampTimelineStarted,
} from './timelineMessageClock'

describe('timelineMessageClock', () => {
  it('stamps start once; end may move forward', () => {
    const msg: ChatMessage = { role: 'assistant', content: '', status: 'streaming' }
    stampTimelineStarted(msg, 1000)
    stampTimelineStarted(msg, 2000)
    expect(msg.timelineStartedAt).toBe(1000)
    stampTimelineEnded(msg, 5000)
    stampTimelineEnded(msg, 9000)
    expect(msg.timelineEndedAt).toBe(9000)
    stampTimelineEnded(msg, 8000)
    expect(msg.timelineEndedAt).toBe(9000)
  })

  it('hydrates from createdAt/updatedAt for terminal messages', () => {
    const msg: ChatMessage = {
      role: 'assistant',
      content: 'ok',
      status: 'completed',
      createdAt: '2026-07-20T00:00:00.000Z',
      updatedAt: '2026-07-20T00:00:10.000Z',
    }
    hydrateTimelineBoundsFromMessageTimes(msg)
    expect(msg.timelineStartedAt).toBe(Date.parse('2026-07-20T00:00:00.000Z'))
    expect(msg.timelineEndedAt).toBe(Date.parse('2026-07-20T00:00:10.000Z'))
  })
})
