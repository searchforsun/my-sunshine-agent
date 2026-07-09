import type { ChatMessage } from './chat'
import { stepsHaveAwaitingHitl } from './hitlSteps'
import { isPlanApprovalAwaiting } from './planApprovalSteps'
import { useConversationAttention, type ConversationAttentionKind } from '../composables/useConversationAttention'
import { useChatViewport } from '../composables/useChatViewport'

/** 是否存在需用户操作的确认项（HITL / 执行计划确认等） */
export function messageHasPendingConfirmation(msg: ChatMessage | undefined): boolean {
  if (!msg || msg.role !== 'assistant') return false
  if (stepsHaveAwaitingHitl(msg.steps) || !!msg.pendingHitlConfirmation) return true
  return msg.steps?.some(step => isPlanApprovalAwaiting(step)) ?? false
}

/** 用户未在底部查看该会话时，标记侧栏/气泡提醒 */
export function notifyConversationAttention(
  convId: string,
  kind: ConversationAttentionKind,
  lastAssistant?: ChatMessage,
): void {
  if (!convId) return
  if (kind === 'hitl_pending' && lastAssistant && !messageHasPendingConfirmation(lastAssistant)) return
  const { isUserWatchingConversation } = useChatViewport()
  if (isUserWatchingConversation(convId)) return
  useConversationAttention().setAttention(convId, kind)
}

export function notifyHitlIfNeeded(convId: string, lastAssistant?: ChatMessage): void {
  if (!messageHasPendingConfirmation(lastAssistant)) return
  notifyConversationAttention(convId, 'hitl_pending', lastAssistant)
}

export function notifyCompletedIfNeeded(convId: string, lastAssistant?: ChatMessage): void {
  if (lastAssistant?.role === 'assistant' && messageHasPendingConfirmation(lastAssistant)) {
    notifyConversationAttention(convId, 'hitl_pending', lastAssistant)
    return
  }
  notifyConversationAttention(convId, 'completed', lastAssistant)
}
