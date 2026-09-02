import { describe, expect, it } from 'vitest'
import {
  CHAT_REPIN_THRESHOLD_PX,
  CHAT_UNPIN_THRESHOLD_PX,
  distanceFromChatBottom,
  resolveChatScrollPinned,
} from './chatScrollPin'

describe('resolveChatScrollPinned', () => {
  it('unpins when user leaves bottom even while suppressed', () => {
    expect(resolveChatScrollPinned({
      distanceFromBottom: CHAT_UNPIN_THRESHOLD_PX + 1,
      suppressed: true,
      currentlyPinned: true,
    })).toBe(false)
  })

  it('unpins on scrolledUp even within threshold (scrollbar / touch)', () => {
    expect(resolveChatScrollPinned({
      distanceFromBottom: 8,
      suppressed: true,
      currentlyPinned: true,
      scrolledUp: true,
    })).toBe(false)
  })

  it('keeps pin during suppress if still near bottom', () => {
    expect(resolveChatScrollPinned({
      distanceFromBottom: 0,
      suppressed: true,
      currentlyPinned: true,
    })).toBe(true)
  })

  it('repins only within tight threshold', () => {
    expect(resolveChatScrollPinned({
      distanceFromBottom: CHAT_REPIN_THRESHOLD_PX,
      suppressed: false,
      currentlyPinned: false,
    })).toBe(true)
    expect(resolveChatScrollPinned({
      distanceFromBottom: CHAT_REPIN_THRESHOLD_PX + 1,
      suppressed: false,
      currentlyPinned: false,
    })).toBe(false)
  })

  it('stays unpinned in the hysteresis band', () => {
    expect(resolveChatScrollPinned({
      distanceFromBottom: CHAT_UNPIN_THRESHOLD_PX - 1,
      suppressed: false,
      currentlyPinned: false,
    })).toBe(false)
  })
})

describe('distanceFromChatBottom', () => {
  it('computes remaining scroll', () => {
    expect(distanceFromChatBottom({
      scrollHeight: 1000,
      scrollTop: 800,
      clientHeight: 100,
    })).toBe(100)
  })
})
