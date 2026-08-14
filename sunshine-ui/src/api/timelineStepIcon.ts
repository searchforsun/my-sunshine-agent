import type { ProcessingStep } from './processingSteps'
import { isDecisionStep, isSubagentStep } from './processingSteps'
import { isThinkStepId } from './processingStepsNormalize'
import { isHarnessPlanStep, isWorkerStep } from './harnessHierarchy'
import { isRagStepId, isToolStepId } from './hitlSteps'
import {
  catalogToolIdFromStepId,
  sandboxToolKind,
} from './processingStepsDisplay'

export type TimelineStepKind =
  | 'decision' | 'subagent' | 'worker' | 'plan'
  | 'rag' | 'intent' | 'skill' | 'tasks' | 'think'
  | 'tool-view' | 'tool-edit' | 'tool-fetch' | 'tool-exec' | 'tool'
  | 'generic'

/** 判别优先级：决策 > 子智能体 > worker > plan > 检索 > intent > skill > tasks > think > 工具细分 > 兜底 */
export function resolveTimelineStepKind(step: ProcessingStep): TimelineStepKind {
  if (isDecisionStep(step)) return 'decision'
  if (isSubagentStep(step)) return 'subagent'
  if (isWorkerStep(step)) return 'worker'
  if (isHarnessPlanStep(step)) return 'plan'
  if (isRagStepId(step.id)) return 'rag'
  if (step.phase === 'intent') return 'intent'
  if (step.phase === 'skill') return 'skill'
  if (step.phase === 'tasks') return 'tasks'
  if (step.phase === 'plan') return 'plan'
  if (isThinkStepId(step.id)) return 'think'
  if (isToolStepId(step.id)) {
    const toolId = catalogToolIdFromStepId(step.id)
    const sandboxKind = sandboxToolKind(toolId)
    if (sandboxKind) return `tool-${sandboxKind}` as TimelineStepKind
    return 'tool'
  }
  return 'generic'
}
