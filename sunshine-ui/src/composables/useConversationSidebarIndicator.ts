import type { ChatMessage } from '../api/chat'
import { getSessionRegistry } from '../api/chatSessionRegistry'
import {
  messageHasAwaitingDecision,
  messageHasPendingHitl,
} from '../api/conversationAttentionNotify'
import { useConversationAttention, type ConversationAttentionKind } from './useConversationAttention'

/** decision_pending=待决策（黄？）；hitl_pending=待确认（黄！） */
export type SidebarConvIndicator = ConversationAttentionKind | 'streaming' | 'decision_pending'

type ConvSlice = { id: string; messages?: ChatMessage[] }

function isUserActionPending(ind: SidebarConvIndicator | null): boolean {
  return ind === 'decision_pending' || ind === 'hitl_pending'
}

/** 侧栏会话圆点：待决策/待确认 > 流式中 > 已完成（含实时消息态，不仅 attention 缓存） */
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
    const last = lastAssistant(msgs)
    // 决策问卷用黄？；工具 HITL 用黄！——不可混用
    if (messageHasAwaitingDecision(last)) return 'decision_pending'
    if (messageHasPendingHitl(last)) return 'hitl_pending'
    if (getAttention(convId) === 'hitl_pending') return 'hitl_pending'
    if (session?.loading) return 'streaming'
    if (getAttention(convId) === 'completed') return 'completed'
    return null
  }

  function navMenuIndicator(conversations: ConvSlice[]): SidebarConvIndicator | null {
    let streaming = false
    let hitl = false
    for (const conv of conversations) {
      const ind = resolveIndicator(conv.id, conv.messages)
      if (ind === 'decision_pending') return 'decision_pending'
      if (ind === 'hitl_pending') hitl = true
      if (ind === 'streaming') streaming = true
    }
    if (hitl) return 'hitl_pending'
    if (streaming) return 'streaming'
    for (const conv of conversations) {
      if (resolveIndicator(conv.id, conv.messages) === 'completed') return 'completed'
    }
    return null
  }

  function pickPendingConversation(conversations: ConvSlice[]): string | null {
    for (const conv of conversations) {
      if (isUserActionPending(resolveIndicator(conv.id, conv.messages))) return conv.id
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
