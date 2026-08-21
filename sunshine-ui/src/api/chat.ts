import type { ProcessingStep } from './processingSteps'
import type { ExecutionMode } from './executionModes'
import type { HitlConfirmationPayload } from './hitlSteps'
import type { ContentBlock } from './contentInterleave'

/** 消息级 LLM usage（SSE type=usage 末帧 / 历史 usage_json） */
export interface MessageUsage {
  inputTokens: number
  outputTokens: number
  llmCalls: number
  contextTokens?: number
  contextWindowTokens?: number
  contextPercent?: number
  /** 缓存命中率 = Σcached / Σinput（消息级累计，0–100） */
  cachedPercent?: number
  groups?: Record<string, number>
}

export interface ChatMessage {
  id?: string
  role: 'user' | 'assistant'
  content: string
  /** user 消息发送时的 executionPreference */
  executionPreference?: ExecutionMode
  /** 模型推理过程（SSE type:reasoning，不落库） */
  reasoning?: string
  /** 后端处理流水线步骤（SSE type:step） */
  steps?: ProcessingStep[]
  /** ReAct：正文按步骤锚点分段，与 OperationStack 穿插展示 */
  contentBlocks?: ContentBlock[]
  /** SSE confirmation 先于 tool 步骤到达时的暂存（按 token 多条） */
  pendingHitlConfirmations?: HitlConfirmationPayload[]
  status?: 'streaming' | 'interrupted' | 'failed' | 'completed'
  /** 流式失败时的用户可见错误（与正文分离展示） */
  streamError?: string
  intent?: string
  executionPlanId?: string
  /** 消息创建时间（API ISO 或 epoch ms）— 总览墙钟 start 兜底 */
  createdAt?: string | number
  /** 消息最后更新（API）— 终态总览墙钟 end 兜底 */
  updatedAt?: string | number
  /** 会话内自增序号 — 历史游标分页 beforeSeq 基准 */
  seq?: number
  /** 前端墙钟：进入 streaming 时写入 */
  timelineStartedAt?: number
  /** 前端墙钟：正文结束 / 消息终态时写入 */
  timelineEndedAt?: number
  /** 消息级 LLM usage（SSE type=usage 末帧 / 历史 usage_json） */
  usage?: MessageUsage
}
