import { ref, type Ref } from 'vue'

/** Chat 滚动区：流式贴底与用户手动上滑 */
export function useChatScroll(loading: Ref<boolean>) {
  const scrollRef = ref<HTMLElement | null>(null)
  const chatScrollPinned = ref(true)
  const forceChatScroll = ref(false)

  function isNearChatBottom(el: HTMLElement, threshold = 96): boolean {
    return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold
  }

  function onChatScroll() {
    const el = scrollRef.value
    if (!el || !loading.value) return
    chatScrollPinned.value = isNearChatBottom(el)
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
    requestAnimationFrame(apply)
  }

  function pinScrollForHitl() {
    chatScrollPinned.value = true
    forceChatScroll.value = true
  }

  return {
    scrollRef,
    chatScrollPinned,
    forceChatScroll,
    onChatScroll,
    scrollToBottom,
    pinScrollForHitl,
  }
}
