import { ref } from 'vue'

/** 当前 Chat 页是否可见、用户是否在底部 — 用于决定是否弹出完成/待确认提示 */
const chatRouteActive = ref(false)
const activeConversationId = ref<string | null>(null)
const scrollPinnedToBottom = ref(true)

export function useChatViewport() {
  function setChatRouteActive(active: boolean): void {
    chatRouteActive.value = active
  }

  function setActiveConversation(id: string | null): void {
    activeConversationId.value = id
  }

  function setScrollPinned(pinned: boolean): void {
    scrollPinnedToBottom.value = pinned
  }

  /** 用户正在该会话 Chat 页且滚在底部（无需气泡/红点） */
  function isUserWatchingConversation(convId: string): boolean {
    return chatRouteActive.value
      && activeConversationId.value === convId
      && scrollPinnedToBottom.value
  }

  return {
    chatRouteActive,
    activeConversationId,
    scrollPinnedToBottom,
    setChatRouteActive,
    setActiveConversation,
    setScrollPinned,
    isUserWatchingConversation,
  }
}
