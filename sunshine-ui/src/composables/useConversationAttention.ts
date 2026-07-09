import { reactive, ref } from 'vue'

/** completed=回答完成（红点）；hitl_pending=待确认（黄点） */
export type ConversationAttentionKind = 'completed' | 'hitl_pending'

const attentionByConv = reactive(new Map<string, ConversationAttentionKind>())
const pendingScrollConvId = ref<string | null>(null)

export function useConversationAttention() {
  function setAttention(convId: string, kind: ConversationAttentionKind): void {
    if (!convId) return
    attentionByConv.set(convId, kind)
  }

  function clearAttention(convId: string): void {
    if (!convId) return
    attentionByConv.delete(convId)
  }

  function getAttention(convId: string): ConversationAttentionKind | undefined {
    return attentionByConv.get(convId)
  }

  function hasAnyAttention(): boolean {
    return attentionByConv.size > 0
  }

  /** 导航「AI 对话」时优先打开有待办的会话 */
  function pickAttentionConversation(): string | null {
    for (const [id, kind] of attentionByConv.entries()) {
      if (kind === 'hitl_pending') return id
    }
    const first = attentionByConv.keys().next()
    return first.done ? null : first.value
  }

  function navMenuAttention(): ConversationAttentionKind | null {
    for (const kind of attentionByConv.values()) {
      if (kind === 'hitl_pending') return 'hitl_pending'
    }
    return attentionByConv.size > 0 ? 'completed' : null
  }

  function requestScrollToBottom(convId: string): void {
    pendingScrollConvId.value = convId
  }

  function consumeScrollRequest(convId: string): boolean {
    if (pendingScrollConvId.value !== convId) return false
    pendingScrollConvId.value = null
    return true
  }

  return {
    attentionByConv,
    pendingScrollConvId,
    setAttention,
    clearAttention,
    getAttention,
    hasAnyAttention,
    pickAttentionConversation,
    navMenuAttention,
    requestScrollToBottom,
    consumeScrollRequest,
  }
}
