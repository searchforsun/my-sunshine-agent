/**
 * 时间线总览墙钟：含正文流式；终态写入 timelineEndedAt，刷新用消息 createdAt/updatedAt。
 */
import type { ChatMessage } from './chat'

/**
 * 每条消息都有创建时间戳（user / assistant 一视同仁），作为跨轮次排序唯一权威。
 * - 流式：进入时 stampTimelineStarted 写入 timelineStartedAt（见 chatSessions.send），
 *   同时为本地消息兜底写入 createdAt。
 * - 历史：API 每条都带 createdAt（MessageDto.Instant），直接读取。
 */
export function messageTimestamp(msg: ChatMessage): number {
  const created = toEpochMs(msg.createdAt)
  if (created != null) return created
  // 实测本地消息都在创建时经 stampTimelineStarted 兜底写入了 createdAt；
  // 若仍缺失（异常态），按最早处理，绝不混入 updatedAt/timelineStartedAt 破坏唯一权威。
  return Number.MIN_SAFE_INTEGER
}

export function stampTimelineStarted(msg: ChatMessage, atMs: number = Date.now()): void {
  if (msg.timelineStartedAt == null) msg.timelineStartedAt = atMs
  // 创建时间戳兜底：本地消息在发送瞬间即持有 createdAt，作为排序键（创建时间）始终存在。
  if (msg.createdAt == null) msg.createdAt = atMs
}

/** 允许延后抬高（SSE completed 偏早时，finally / 末包仍可修正） */
export function stampTimelineEnded(msg: ChatMessage, atMs: number = Date.now()): void {
  if (msg.timelineEndedAt == null || atMs > msg.timelineEndedAt) {
    msg.timelineEndedAt = atMs
  }
}

/** 从 API createdAt/updatedAt（ISO 或 epoch）补全墙钟边界 — 对 user / assistant 统一生效 */
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
