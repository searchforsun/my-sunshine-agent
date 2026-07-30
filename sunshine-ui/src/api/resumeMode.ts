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

/** 多智能体协作助手消息 */
export function isPeerCollabAssistantMessage(msg: Pick<ChatMessage, 'intent' | 'steps'>): boolean {
  const intent = (msg.intent ?? '').toLowerCase()
  if (intent === 'peer-collab') return true
  return msg.steps?.some(s =>
    s.id === 'expert-convene'
    || s.phase === 'expert-convene'
    || s.phase === 'expert'
    || (s.id?.startsWith('expert-') ?? false)) ?? false
}

/** ReAct 助手消息（非 Plan/Workflow / 多智能体协作） */
export function isReactAssistantMessage(msg: Pick<ChatMessage, 'intent' | 'steps'>): boolean {
  if (isPeerCollabAssistantMessage(msg)) return false
  if (msg.steps?.some(s => s.phase === 'plan' || s.id.startsWith('node-'))) return false
  const intent = (msg.intent ?? '').toLowerCase()
  if (intent.startsWith('workflow:') || intent === 'plan-workflow') return false
  if (intent === 'knowledge' || intent === 'finance') return false
  return true
}

/** 续跑时清空步骤并从意图步重跑（ReAct 或多智能体协作） */
export function isExecutionRestartMessage(msg: Pick<ChatMessage, 'intent' | 'steps'>): boolean {
  return isReactAssistantMessage(msg) || isPeerCollabAssistantMessage(msg)
}
