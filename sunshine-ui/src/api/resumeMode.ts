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
  // ReAct「继续生成」= 接着进度（同 id 重放 + 后端注入块），勿走 regenerate 清空路径
  if (isReactAssistantMessage(msg)) return 'checkpoint'
  if (hasPausedNode(msg.steps) || hasAwaitingInteraction(msg.steps)) {
    return 'checkpoint'
  }
  if (hasPlanStep(msg.steps) && msg.status === 'interrupted') {
    return 'planning'
  }
  return 'regenerate'
}

export function resumeButtonLabel(msg: ChatMessage): string {
  if (isReactAssistantMessage(msg)) return '继续生成'
  const mode = resolveResumeMode(msg)
  if (mode === 'checkpoint') return '继续执行'
  if (mode === 'planning') return '继续执行计划'
  return '继续生成'
}

/** ReAct 助手消息（非 Plan/Workflow） */
export function isReactAssistantMessage(msg: Pick<ChatMessage, 'intent' | 'steps'>): boolean {
  if (msg.steps?.some(s => s.phase === 'plan' || s.id.startsWith('node-'))) return false
  const intent = (msg.intent ?? '').toLowerCase()
  if (intent.startsWith('workflow:') || intent === 'plan-workflow') return false
  if (intent === 'knowledge' || intent === 'finance') return false
  return true
}

/**
 * 历史：ReAct 曾走「执行重启」清空路径。产品语义改为无感接着进度后恒为 false；
 * 保留导出以免旧调用方编译失败。
 */
export function isExecutionRestartMessage(_msg: Pick<ChatMessage, 'intent' | 'steps'>): boolean {
  return false
}
