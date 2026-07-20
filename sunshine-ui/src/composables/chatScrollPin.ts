/** 距底超过此值 → 取消贴底（用户上滑） */
export const CHAT_UNPIN_THRESHOLD_PX = 48
/** 距底小于此值 → 恢复贴底（需比 unpin 更紧，避免抖） */
export const CHAT_REPIN_THRESHOLD_PX = 24

export function distanceFromChatBottom(el: {
  scrollHeight: number
  scrollTop: number
  clientHeight: number
}): number {
  return el.scrollHeight - el.scrollTop - el.clientHeight
}

/**
 * 程序化贴底会短暂 suppress scroll 同步；此时仍须允许「已离开底部」→ 取消贴底，
 * 否则正文流式时 scrollbar/触控上滑会被下一帧 follow 拽回（卡手/抖动）。
 *
 * `scrolledUp`：相对上次 scrollTop 上移（拖动条/触控无 wheel 时的主信号）。
 */
export function resolveChatScrollPinned(opts: {
  distanceFromBottom: number
  suppressed: boolean
  currentlyPinned: boolean
  scrolledUp?: boolean
}): boolean {
  if (opts.scrolledUp && opts.distanceFromBottom > 1) return false
  const d = opts.distanceFromBottom
  if (d > CHAT_UNPIN_THRESHOLD_PX) return false
  if (opts.suppressed) return opts.currentlyPinned
  if (d <= CHAT_REPIN_THRESHOLD_PX) return true
  return opts.currentlyPinned
}
