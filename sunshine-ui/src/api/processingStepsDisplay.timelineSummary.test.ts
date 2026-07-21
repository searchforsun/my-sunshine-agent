import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import {
  formatElapsedClock,
  resolveTimelineElapsedMs,
  resolveTimelineSummaryPrefix,
  formatTimelineSummaryText,
} from './processingStepsDisplay'

describe('formatElapsedClock', () => {
  it('formats seconds and minutes per spec', () => {
    expect(formatElapsedClock(Number.NaN)).toBe('')
    expect(formatElapsedClock(-1)).toBe('')
    expect(formatElapsedClock(0)).toBe('0s')
    expect(formatElapsedClock(999)).toBe('0s')
    expect(formatElapsedClock(42_000)).toBe('42s')
    expect(formatElapsedClock(80_000)).toBe('1m 20s')
    expect(formatElapsedClock(120_000)).toBe('2m 0s')
  })
})

describe('resolveTimelineElapsedMs', () => {
  const step = (partial: Partial<ProcessingStep>): ProcessingStep => ({
    id: 'intent',
    phase: 'intent',
    lifecycle: 'done',
    ...partial,
  })

  it('uses min startedAt to now when live and no client start', () => {
    const ms = resolveTimelineElapsedMs({
      steps: [step({ startedAt: 1_000, ts: 9_000 }), step({ id: 'think', startedAt: 2_000 })],
      live: true,
      nowMs: 81_000,
    })
    expect(ms).toBe(80_000)
  })

  it('prefers client timelineStartedAt over server step startedAt (no clock skew drop)', () => {
    const ms = resolveTimelineElapsedMs({
      steps: [step({ startedAt: 6_000, endedAt: 10_000 })],
      live: true,
      nowMs: 21_000,
      fallbackStartMs: 1_000,
    })
    expect(ms).toBe(20_000)
  })

  it('uses fallbackStart when steps lack timestamps', () => {
    const ms = resolveTimelineElapsedMs({
      steps: [step({})],
      live: true,
      nowMs: 5_000,
      fallbackStartMs: 1_000,
    })
    expect(ms).toBe(4_000)
  })

  it('prefers timelineEndedAt over step endedAt when not live', () => {
    expect(resolveTimelineElapsedMs({
      steps: [step({ startedAt: 1_000, endedAt: 3_000 })],
      live: false,
      fallbackStartMs: 1_000,
      fallbackEndMs: 21_000,
    })).toBe(20_000)
  })

  it('falls back to max endedAt when no timelineEndedAt', () => {
    expect(resolveTimelineElapsedMs({
      steps: [step({ startedAt: 1_000, endedAt: 3_000 }), step({ id: 't', startedAt: 1_500, endedAt: 4_000 })],
      live: false,
    })).toBe(3_000)
  })

  it('returns undefined when no start', () => {
    expect(resolveTimelineElapsedMs({ steps: [step({})], live: false })).toBeUndefined()
  })
})

describe('resolveTimelineSummaryPrefix', () => {
  it('maps status and live', () => {
    expect(resolveTimelineSummaryPrefix({ live: true })).toBe('正在处理')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'streaming' })).toBe('正在处理')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'completed' })).toBe('已完成')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'interrupted' })).toBe('已中断')
    expect(resolveTimelineSummaryPrefix({ live: false, messageStatus: 'failed' })).toBe('已失败')
    expect(resolveTimelineSummaryPrefix({ live: false })).toBe('已完成')
  })
})

describe('formatTimelineSummaryText', () => {
  it('joins prefix and clock', () => {
    expect(formatTimelineSummaryText('已完成', '1m 20s')).toBe('已完成 1m 20s')
    expect(formatTimelineSummaryText('已失败', '')).toBe('已失败')
  })
})
