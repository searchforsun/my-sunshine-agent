/** 会话创建时间展示与按天分组 */

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

export function startOfLocalDay(ts: number): number {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export function daysBeforeToday(ts: number, now = Date.now()): number {
  const todayStart = startOfLocalDay(now)
  const dayStart = startOfLocalDay(ts)
  return Math.floor((todayStart - dayStart) / 86_400_000)
}

/** 侧栏分组标题：今天 / 昨天 / M月D日 / 更早 */
export function conversationDayLabel(ts: number, now = Date.now()): string {
  const ago = daysBeforeToday(ts, now)
  if (ago >= 7) return '更早'
  if (ago === 0) return '今天'
  if (ago === 1) return '昨天'
  const d = new Date(ts)
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

/** 简洁时间：刚刚 / N分钟前 / 今天 HH:mm / 昨天 HH:mm / M月D日 HH:mm */
export function formatConversationTime(ts: number, now = Date.now()): string {
  const diff = now - ts
  const ago = daysBeforeToday(ts, now)
  const d = new Date(ts)
  const clock = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
  if (ago === 0) {
    if (diff < 60_000) return '刚刚'
    if (diff < 3_600_000) return `${Math.max(1, Math.floor(diff / 60_000))}分钟前`
    return `今天 ${clock}`
  }
  if (ago === 1) return `昨天 ${clock}`
  if (ago < 7) return `${d.getMonth() + 1}月${d.getDate()}日 ${clock}`
  return `${d.getMonth() + 1}/${d.getDate()}`
}

/** 侧栏列表项时间（已按天分组，不带日期前缀）：刚刚 / N分钟前 / HH:mm / M/D */
export function formatSidebarItemTime(ts: number, now = Date.now()): string {
  const diff = now - ts
  const ago = daysBeforeToday(ts, now)
  const d = new Date(ts)
  const clock = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
  if (ago === 0) {
    if (diff < 60_000) return '刚刚'
    if (diff < 3_600_000) return `${Math.max(1, Math.floor(diff / 60_000))}分钟前`
    return clock
  }
  if (ago < 7) return clock
  return `${d.getMonth() + 1}/${d.getDate()}`
}

export type ConversationDayBucketKey = `day:${number}` | 'older'

export function conversationDayBucketKey(ts: number, now = Date.now()): ConversationDayBucketKey {
  if (daysBeforeToday(ts, now) >= 7) return 'older'
  return `day:${startOfLocalDay(ts)}`
}
