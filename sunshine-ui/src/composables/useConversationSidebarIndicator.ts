import type { ChatMessage } from '../api/chat'
import { getSessionRegistry } from '../api/chatSessionRegistry'
import { messageHasPendingConfirmation } from '../api/conversationAttentionNotify'
import { useConversationAttention, type ConversationAttentionKind } from './useConversationAttention'

export type SidebarConvIndicator = ConversationAttentionKind | 'streaming'

type ConvSlice = { id: string; messages?: ChatMessage[] }

/** 侧栏会话圆点：待确认 > 流式中 > 已完成（含实时消息态，不仅 attention 缓存） */
export function useConversationSidebarIndicator() {
  const { getAttention, attentionByConv } = useConversationAttention()

  function touchSessions(): void {
    for (const s of getSessionRegistry().values()) {
      void s.loading
      void s.streamRevision
    }
  }

  function lastAssistant(msgs: ChatMessage[] | undefined): ChatMessage | undefined {
    if (!msgs?.length) return undefined
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'assistant') return msgs[i]
    }
    return undefined
  }

  function resolveIndicator(convId: string, storeMessages?: ChatMessage[]): SidebarConvIndicator | null {
    touchSessions()
    void attentionByConv.size
    const session = getSessionRegistry().get(convId)
    const msgs = session?.messages?.length ? session.messages : (storeMessages ?? [])
    if (messageHasPendingConfirmation(lastAssistant(msgs))) return 'hitl_pending'
    if (getAttention(convId) === 'hitl_pending') return 'hitl_pending'
    if (session?.loading) return 'streaming'
    if (getAttention(convId) === 'completed') return 'completed'
    return null
  }

  function navMenuIndicator(conversations: ConvSlice[]): SidebarConvIndicator | null {
    let streaming = false
    for (const conv of conversations) {
      const ind = resolveIndicator(conv.id, conv.messages)
      if (ind === 'hitl_pending') return 'hitl_pending'
      if (ind === 'streaming') streaming = true
    }
    if (streaming) return 'streaming'
    for (const conv of conversations) {
      if (resolveIndicator(conv.id, conv.messages) === 'completed') return 'completed'
    }
    return null
  }

  function pickPendingConversation(conversations: ConvSlice[]): string | null {
    for (const conv of conversations) {
      if (resolveIndicator(conv.id, conv.messages) === 'hitl_pending') return conv.id
    }
    return null
  }

  function pickStreamingConversation(conversations: ConvSlice[]): string | null {
    for (const conv of conversations) {
      if (resolveIndicator(conv.id, conv.messages) === 'streaming') return conv.id
    }
    return null
  }

  return {
    resolveIndicator,
    navMenuIndicator,
    pickPendingConversation,
    pickStreamingConversation,
  }
}
