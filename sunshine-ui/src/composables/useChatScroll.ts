import { ref, type Ref } from 'vue'

/** Chat 滚动区：流式贴底与用户手动上滑 */
export function useChatScroll(loading: Ref<boolean>) {
  const scrollRef = ref<HTMLElement | null>(null)
  const chatScrollPinned = ref(true)
  const forceChatScroll = ref(false)

  function isNearChatBottom(el: HTMLElement, threshold = 96): boolean {
    return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold
  }

  function syncScrollPinned(): void {
    const el = scrollRef.value
    if (!el) return
    chatScrollPinned.value = isNearChatBottom(el)
  }

  function onChatScroll() {
    syncScrollPinned()
  }

  function scrollToBottom(force = false) {
    const el = scrollRef.value
    if (!el) return
    const shouldScroll = force || forceChatScroll.value || chatScrollPinned.value
    if (!shouldScroll) return
    const apply = () => {
      el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight)
    }
    apply()
    requestAnimationFrame(() => {
      apply()
      requestAnimationFrame(apply)
    })
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
    scrollToBottom,
    pinScrollForSend,
    pinScrollForHitl,
    forwardWheelToChatScroll,
    syncScrollPinned,
    isNearChatBottom,
  }
}
