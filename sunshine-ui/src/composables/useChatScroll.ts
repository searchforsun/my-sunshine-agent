import { ref, type Ref } from 'vue'
import {
  distanceFromChatBottom,
  resolveChatScrollPinned,
} from './chatScrollPin'

/** 时间线/工具展开区内层滚动；触顶/触底时把滚轮交给外层 chat-scroll */
const NESTED_SCROLL_SEL = [
  '.op-detail',
  '.expand-scroll',
  '.taskboard-list',
  '.peer-detail',
  '.chat-meta-body',
].join(',')

/** Chat 滚动区：流式贴底与用户手动上滑 */
export function useChatScroll(_loading: Ref<boolean>) {
  const scrollRef = ref<HTMLElement | null>(null)
  const chatScrollPinned = ref(true)
  const forceChatScroll = ref(false)
  /** 程序化贴底产生的 scroll 事件勿改写 pinned（但仍允许「已离开底部」取消贴底） */
  let pinSyncSuppressed = false
  let pinSyncSettleRaf = 0
  /** 流式跟随合并到每帧最多一次，避免 step/reasoning 洪水抢滚轮 */
  let followRaf = 0
  let lastScrollTop = 0

  function isNearChatBottom(el: HTMLElement, threshold = 96): boolean {
    return distanceFromChatBottom(el) <= threshold
  }

  function cancelFollowRaf(): void {
    if (!followRaf) return
    cancelAnimationFrame(followRaf)
    followRaf = 0
  }

  function unpinFromUser(): void {
    chatScrollPinned.value = false
    cancelFollowRaf()
    pinSyncSuppressed = false
    if (pinSyncSettleRaf) {
      cancelAnimationFrame(pinSyncSettleRaf)
      pinSyncSettleRaf = 0
    }
  }

  function suppressPinSyncBriefly(): void {
    pinSyncSuppressed = true
    if (pinSyncSettleRaf) cancelAnimationFrame(pinSyncSettleRaf)
    // 等强制贴底的双 rAF 写完再恢复，避免把 pinned 锁死在 true
    pinSyncSettleRaf = requestAnimationFrame(() => {
      pinSyncSettleRaf = requestAnimationFrame(() => {
        pinSyncSettleRaf = 0
        pinSyncSuppressed = false
      })
    })
  }

  function syncScrollPinned(): void {
    const el = scrollRef.value
    if (!el) return
    const top = el.scrollTop
    const scrolledUp = top < lastScrollTop - 0.5
    lastScrollTop = top
    chatScrollPinned.value = resolveChatScrollPinned({
      distanceFromBottom: distanceFromChatBottom(el),
      suppressed: pinSyncSuppressed,
      currentlyPinned: chatScrollPinned.value,
      scrolledUp,
    })
    // 用户已离开底部：立刻解除 suppress，避免后续 follow 再抢
    if (!chatScrollPinned.value) {
      pinSyncSuppressed = false
      cancelFollowRaf()
    }
  }

  function onChatScroll() {
    syncScrollPinned()
  }

  function applyScrollBottom(): void {
    const el = scrollRef.value
    if (!el) return
    suppressPinSyncBriefly()
    const nextTop = Math.max(0, el.scrollHeight - el.clientHeight)
    el.scrollTop = nextTop
    // 程序化贴底：同步 lastScrollTop，避免下一帧被误判为用户上滑
    lastScrollTop = nextTop
  }

  function scrollToBottom(force = false) {
    const el = scrollRef.value
    if (!el) return
    const shouldScroll = force || forceChatScroll.value || chatScrollPinned.value
    if (!shouldScroll) return
    if (force || forceChatScroll.value) {
      applyScrollBottom()
      requestAnimationFrame(() => {
        applyScrollBottom()
        requestAnimationFrame(applyScrollBottom)
      })
      return
    }
    if (followRaf) return
    followRaf = requestAnimationFrame(() => {
      followRaf = 0
      if (!chatScrollPinned.value && !forceChatScroll.value) return
      applyScrollBottom()
    })
  }

  /**
   * 捕获阶段：上滑立即取消贴底；内层触顶/触底时把滚轮交给外层。
   * 解决非正文阶段指针在时间线/展开区时无法上滑的问题。
   */
  function onChatWheelCapture(e: WheelEvent): void {
    if (e.deltaY < 0) {
      unpinFromUser()
    }
    const el = scrollRef.value
    if (!el || el.scrollHeight <= el.clientHeight) return
    const target = e.target
    if (!(target instanceof Element)) return
    const nested = target.closest(NESTED_SCROLL_SEL)
    if (!(nested instanceof HTMLElement) || nested === el) return
    const atTop = nested.scrollTop <= 0
    const atBottom = nested.scrollTop + nested.clientHeight >= nested.scrollHeight - 1
    if (e.deltaY < 0 && atTop) {
      el.scrollTop = Math.max(0, el.scrollTop + e.deltaY)
      e.preventDefault()
      return
    }
    if (e.deltaY > 0 && atBottom) {
      el.scrollTop = Math.min(
        el.scrollHeight - el.clientHeight,
        el.scrollTop + e.deltaY,
      )
      syncScrollPinned()
      e.preventDefault()
    }
  }

  /** 用户主动发消息 / 续跑：恢复贴底，流式阶段跟随新内容 */
  function pinScrollForSend() {
    chatScrollPinned.value = true
  }

  function pinScrollForHitl() {
    chatScrollPinned.value = true
    forceChatScroll.value = true
  }

  /** 悬浮输入区拦截滚轮时，转发给消息滚动区 */
  function forwardWheelToChatScroll(e: WheelEvent) {
    const el = scrollRef.value
    if (!el || el.scrollHeight <= el.clientHeight) return
    const target = e.target
    if (target instanceof Element && target.closest('.skill-suggest')) return
    if (e.deltaY < 0) {
      unpinFromUser()
    }
    el.scrollTop = Math.min(
      el.scrollHeight - el.clientHeight,
      Math.max(0, el.scrollTop + e.deltaY),
    )
    syncScrollPinned()
    e.preventDefault()
  }

  return {
    scrollRef,
    chatScrollPinned,
    forceChatScroll,
    onChatScroll,
    onChatWheelCapture,
    scrollToBottom,
    pinScrollForSend,
    pinScrollForHitl,
    forwardWheelToChatScroll,
    syncScrollPinned,
    isNearChatBottom,
  }
}
