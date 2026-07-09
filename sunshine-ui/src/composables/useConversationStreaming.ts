import { getSessionRegistry } from '../api/chatSessionRegistry'

/** 侧栏流式进行中指示 — 读取 session 注册表 loading / streamRevision */
export function useConversationStreaming() {
  function touchRegistry(): void {
    for (const s of getSessionRegistry().values()) {
      void s.loading
      void s.streamRevision
    }
  }

  function isStreaming(convId: string): boolean {
    touchRegistry()
    return getSessionRegistry().get(convId)?.loading ?? false
  }

  function streamingConversationIds(): string[] {
    touchRegistry()
    const ids: string[] = []
    for (const s of getSessionRegistry().values()) {
      if (s.loading) ids.push(s.id)
    }
    return ids
  }

  function hasAnyStreaming(): boolean {
    return streamingConversationIds().length > 0
  }

  /** 「AI 对话」菜单：任一后台会话在流式输出 */
  function navMenuStreaming(): boolean {
    return hasAnyStreaming()
  }

  /** 点击「AI 对话」时优先切到正在流式的会话 */
  function pickStreamingConversation(): string | null {
    return streamingConversationIds()[0] ?? null
  }

  return {
    isStreaming,
    hasAnyStreaming,
    navMenuStreaming,
    pickStreamingConversation,
    streamingConversationIds,
  }
}
