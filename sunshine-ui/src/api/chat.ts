import type { ProcessingStep } from './processingSteps'
import type { ExecutionPreference } from './executionModes'
import type { HitlConfirmationPayload } from './hitlSteps'
import type { ContentBlock } from './contentInterleave'

export interface ChatMessage {
  id?: string
  role: 'user' | 'assistant'
  content: string
  /** user 消息发送时的 executionPreference */
  executionPreference?: ExecutionPreference
  /** 模型推理过程（SSE type:reasoning，不落库） */
  reasoning?: string
  /** 后端处理流水线步骤（SSE type:step） */
  steps?: ProcessingStep[]
  /** ReAct：正文按步骤锚点分段，与 OperationStack 穿插展示 */
  contentBlocks?: ContentBlock[]
  /** SSE confirmation 先于 tool 步骤到达时的暂存（合并成功后清除） */
  pendingHitlConfirmation?: HitlConfirmationPayload
  status?: 'streaming' | 'interrupted' | 'failed' | 'completed'
  /** 流式失败时的用户可见错误（与正文分离展示） */
  streamError?: string
  intent?: string
  executionPlanId?: string
}
