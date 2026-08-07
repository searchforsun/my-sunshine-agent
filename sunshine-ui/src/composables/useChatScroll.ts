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
  /** 用户手动离开底部后置位：流式跟随立即停止，直到重新贴底。解决拖拽滚动条（无 wheel）上滑被贴底 rAF 抢回导致的抖动 */
  let userTakenOver = false
  /** settle 代际计数器：新 settle 启动时递增，旧循环检测到落后代际自动退出 */
  let settleGeneration = 0

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
    userTakenOver = true
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
    const dist = distanceFromChatBottom(el)
    const scrolledUp = top < lastScrollTop - 0.5
    lastScrollTop = top
    // 「仍在底部」优先于 scrollTop 减小：程序化贴底后内容继续变高（表格逐行渲染），
    // 浏览器补发 scroll 时 top < lastScrollTop，但此刻用户其实还在底部，勿误判上滑；
    // 仅「位置不变或向下」时据此 repin —— 贴底态用户上滑经过 dist≤1 帧时保持现状，
    // 交给后续 scrolledUp 帧取消贴底
    if (dist <= 1) {
      if (!scrolledUp) {
        chatScrollPinned.value = true
        userTakenOver = false
      }
      return
    }
    // 贴底态 + suppress 窗口（程序化贴底双 rAF 内）:scroll 链上任何「top 减小」都是
    // 内容变高/布局 settle 的假象（贴底态用户上滑必过 dist≤1 帧），勿接管
    if (pinSyncSuppressed && chatScrollPinned.value) return
    // 用户主动上滑且确已离开底部：立即接管并硬性打断跟随（不等跨帧 ref/watch/rAF），
    // 覆盖拖拽滚动条 / 触控板等不产生 wheel 事件的上滑路径
    if (scrolledUp && dist > 1) {
      unpinFromUser()
      return
    }
    chatScrollPinned.value = resolveChatScrollPinned({
      distanceFromBottom: dist,
      suppressed: pinSyncSuppressed,
      currentlyPinned: chatScrollPinned.value,
      scrolledUp,
    })
    // 用户回到底部：解除接管闩锁，恢复流式跟随
    if (chatScrollPinned.value) {
      userTakenOver = false
      return
    }
    // 用户已离开底部：立刻解除 suppress，避免后续 follow 再抢
    pinSyncSuppressed = false
    cancelFollowRaf()
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

  /**
   * 初始稳定贴底：刷新/首次进入大会话时，消息与时间线可能分帧渲染（scrollHeight 逐步增高），
   * 单次贴底会停在中间高度。持续贴底直到高度连续稳定或超时；用户上滑立即退出。
   */
  function settleScrollToBottom(timeoutMs = 3000): void {
    const el = scrollRef.value
    if (!el) return
    pinScrollForSend()
    const gen = ++settleGeneration
    applyScrollBottom()
    const start = performance.now()
    let lastHeight = el.scrollHeight
    let lastTop = el.scrollTop
    let stableFrames = 0
    const tick = () => {
      // 新 settle 已启动（切换会话等）→ 本循环立即退出
      if (gen !== settleGeneration) return
      if (userTakenOver) return
      const target = scrollRef.value
      if (!target) return
      if (target.scrollTop < lastTop - 0.5 && distanceFromChatBottom(target) > 1) {
        unpinFromUser()
        return
      }
      applyScrollBottom()
      lastTop = target.scrollTop
      if (target.scrollHeight === lastHeight) {
        stableFrames += 1
      } else {
        stableFrames = 0
        lastHeight = target.scrollHeight
      }
      if (stableFrames >= 10 || performance.now() - start > timeoutMs) return
      requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
  }

  function scrollToBottom(force = false) {
    const el = scrollRef.value
    if (!el) return
    // 用户已接管滚动（手动上滑离开底部）：流式跟随一律不抢，强制贴底除外
    if (userTakenOver && !force && !forceChatScroll.value) return
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
      if (userTakenOver && !forceChatScroll.value) return
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
    userTakenOver = false
    chatScrollPinned.value = true
  }

  function pinScrollForHitl() {
    userTakenOver = false
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
    settleScrollToBottom,
    pinScrollForSend,
    pinScrollForHitl,
    forwardWheelToChatScroll,
    syncScrollPinned,
    isNearChatBottom,
  }
}
