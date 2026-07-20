/**
 * 时间线总览墙钟：含正文流式；终态写入 timelineEndedAt，刷新用消息 createdAt/updatedAt。
 */
import type { ChatMessage } from './chat'

export function stampTimelineStarted(msg: ChatMessage, atMs: number = Date.now()): void {
  if (msg.timelineStartedAt == null) msg.timelineStartedAt = atMs
}

export function stampTimelineEnded(msg: ChatMessage, atMs: number = Date.now()): void {
  if (msg.timelineEndedAt == null) msg.timelineEndedAt = atMs
}

/** 从 API createdAt/updatedAt（ISO 或 epoch）补全墙钟边界 */
export function hydrateTimelineBoundsFromMessageTimes(msg: ChatMessage): void {
  const start = toEpochMs(msg.createdAt)
  const end = toEpochMs(msg.updatedAt)
  if (start != null && msg.timelineStartedAt == null) msg.timelineStartedAt = start
  const terminal = msg.status === 'completed'
    || msg.status === 'interrupted'
    || msg.status === 'failed'
  if (terminal && end != null && msg.timelineEndedAt == null) msg.timelineEndedAt = end
}

function toEpochMs(raw: string | number | undefined): number | undefined {
  if (raw == null) return undefined
  if (typeof raw === 'number' && Number.isFinite(raw)) return raw
  const t = Date.parse(String(raw))
  return Number.isNaN(t) ? undefined : t
}
