import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'
import { isRecoveryAwaiting, stepHasHitlAwaiting } from './recoverySteps'

export type ResumeMode = 'checkpoint' | 'planning' | 'regenerate'

function hasAwaitingInteraction(steps?: ProcessingStep[]): boolean {
  return steps?.some(s => stepHasHitlAwaiting(s) || isRecoveryAwaiting(s)) ?? false
}

function hasPausedNode(steps?: ProcessingStep[]): boolean {
  return steps?.some(s =>
    s.id.startsWith('node-') && s.lifecycle === 'paused') ?? false
}

function hasPlanStep(steps?: ProcessingStep[]): boolean {
  return steps?.some(s => s.phase === 'plan' || s.id === 'plan') ?? false
}

/** 续跑按钮模式：checkpoint / planning / regenerate */
export function resolveResumeMode(msg: ChatMessage): ResumeMode {
  if (isReactAssistantMessage(msg)) return 'regenerate'
  if (hasPausedNode(msg.steps) || hasAwaitingInteraction(msg.steps)) {
    return 'checkpoint'
  }
  if (hasPlanStep(msg.steps) && msg.status === 'interrupted') {
    return 'planning'
  }
  return 'regenerate'
}

export function resumeButtonLabel(msg: ChatMessage): string {
  const mode = resolveResumeMode(msg)
  if (mode === 'checkpoint') return '继续执行'
  if (mode === 'planning') return '继续执行计划'
  return '重新生成'
}

/** ReAct 助手消息（非 Plan/Workflow 静态流） */
export function isReactAssistantMessage(msg: Pick<ChatMessage, 'intent' | 'steps'>): boolean {
  if (msg.steps?.some(s => s.phase === 'plan' || s.id.startsWith('node-'))) return false
  const intent = (msg.intent ?? '').toLowerCase()
  if (intent.startsWith('workflow:') || intent === 'plan-workflow' || intent === 'simple-llm') return false
  if (intent === 'knowledge' || intent === 'finance' || intent === 'simple') return false
  return true
}
