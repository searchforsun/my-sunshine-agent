/**
 * SSE 事件分发 — 未知 type 忽略（forward compatible）
 */
import { normalizeStreamChunk } from './streamInvisible'
import { normalizeStep, type ProcessingStep, type StepDelta } from './processingSteps'

export interface SseMeta {
  type: string
  id?: string
  status?: string
  resume?: boolean
  text?: string
  messageId?: string
  seq?: number
}

export type ParsedSsePayload =
  | { kind: 'meta'; meta: SseMeta }
  | { kind: 'content_start'; segmentId: string; afterStepId: string; nodeStepId?: string }
  | { kind: 'content_end'; segmentId: string; nodeStepId?: string }
  | { kind: 'chunk'; text: string; afterStepId?: string; segmentId?: string; nodeStepId?: string }
  | { kind: 'reasoning'; text: string }
  | { kind: 'step'; step: ProcessingStep }
  | { kind: 'step_delta'; delta: StepDelta }
  | { kind: 'confirmation'; confirmation: ToolConfirmationPayload }
  | { kind: 'error'; text: string }
  | { kind: 'ignore' }

export interface ToolConfirmationPayload {
  toolId: string
  toolDisplayName: string
  paramsSummary: string
  confirmationToken: string
  expiresAt: number
}

type Handler = (obj: Record<string, unknown>) => ParsedSsePayload | null

function asMeta(obj: Record<string, unknown>): SseMeta {
  return {
    type: typeof obj.type === 'string' ? obj.type : '',
    id: typeof obj.id === 'string' ? obj.id : undefined,
    status: typeof obj.status === 'string' ? obj.status : undefined,
    resume: typeof obj.resume === 'boolean' ? obj.resume : undefined,
    text: typeof obj.text === 'string' ? obj.text : undefined,
    messageId: typeof obj.messageId === 'string' ? obj.messageId : undefined,
    seq: typeof obj.seq === 'number' ? obj.seq : undefined,
  }
}

const handlers: Record<string, Handler> = {
  step(obj) {
    const step = normalizeStep(obj)
    return step ? { kind: 'step', step } : { kind: 'ignore' }
  },
  step_delta(obj) {
    const stepId = typeof obj.stepId === 'string' ? obj.stepId : null
    const channel = typeof obj.channel === 'string' ? obj.channel : 'output'
    const text = typeof obj.text === 'string' ? normalizeStreamChunk(obj.text) : ''
    if (!stepId || !text) return { kind: 'ignore' }
    return { kind: 'step_delta', delta: { stepId, channel, text } }
  },
  conversation(obj) {
    return { kind: 'meta', meta: asMeta(obj) }
  },
  message(obj) {
    return { kind: 'meta', meta: asMeta(obj) }
  },
  generation(obj) {
    return { kind: 'meta', meta: asMeta(obj) }
  },
  reasoning(obj) {
    const text = typeof obj.text === 'string' ? normalizeStreamChunk(obj.text) : ''
    if (!text) return { kind: 'ignore' }
    return { kind: 'reasoning', text }
  },
  content_start(obj) {
    const segmentId = typeof obj.segmentId === 'string' ? obj.segmentId : ''
    const afterStepId = typeof obj.afterStepId === 'string' ? obj.afterStepId : ''
    const nodeStepId = typeof obj.nodeStepId === 'string' ? obj.nodeStepId : undefined
    if (!segmentId || !afterStepId) return { kind: 'ignore' }
    return { kind: 'content_start', segmentId, afterStepId, nodeStepId }
  },
  content_end(obj) {
    const segmentId = typeof obj.segmentId === 'string' ? obj.segmentId : ''
    const nodeStepId = typeof obj.nodeStepId === 'string' ? obj.nodeStepId : undefined
    if (!segmentId) return { kind: 'ignore' }
    return { kind: 'content_end', segmentId, nodeStepId }
  },
  content(obj) {
    const text = typeof obj.text === 'string' ? normalizeStreamChunk(obj.text) : ''
    if (!text) return { kind: 'ignore' }
    const segmentId = typeof obj.segmentId === 'string' ? obj.segmentId : undefined
    const afterStepId = typeof obj.afterStepId === 'string' ? obj.afterStepId : undefined
    const nodeStepId = typeof obj.nodeStepId === 'string' ? obj.nodeStepId : undefined
    return { kind: 'chunk', text, afterStepId, segmentId, nodeStepId }
  },
  chunk(obj) {
    const text = typeof obj.text === 'string' ? normalizeStreamChunk(obj.text) : ''
    if (!text) return { kind: 'ignore' }
    const afterStepId = typeof obj.afterStepId === 'string' ? obj.afterStepId : undefined
    return { kind: 'chunk', text, afterStepId }
  },
  confirmation(obj) {
    const token = typeof obj.confirmationToken === 'string' ? obj.confirmationToken : ''
    const toolId = typeof obj.toolId === 'string' ? obj.toolId : ''
    if (!token || !toolId) return { kind: 'ignore' }
    return {
      kind: 'confirmation',
      confirmation: {
        toolId,
        toolDisplayName: typeof obj.toolDisplayName === 'string' ? obj.toolDisplayName : toolId,
        paramsSummary: typeof obj.paramsSummary === 'string' ? obj.paramsSummary : '',
        confirmationToken: token,
        expiresAt: typeof obj.expiresAt === 'number' ? obj.expiresAt : 0,
      },
    }
  },
  error(obj) {
    const text = typeof obj.text === 'string' ? obj.text.trim() : ''
    if (!text) return { kind: 'ignore' }
    return { kind: 'error', text }
  },
}

export function parseSsePayload(data: string): ParsedSsePayload {
  try {
    const obj = JSON.parse(data) as Record<string, unknown>
    const type = typeof obj.type === 'string' ? obj.type : ''
    const handler = handlers[type]
    if (handler) {
      return handler(obj) ?? { kind: 'ignore' }
    }
    return { kind: 'ignore' }
  } catch {
    return { kind: 'chunk', text: normalizeStreamChunk(data) }
  }
}
