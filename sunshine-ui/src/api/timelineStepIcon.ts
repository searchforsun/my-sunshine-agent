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
  | 'answer' | 'external' | 'node'
  | 'rag' | 'intent' | 'skill' | 'tasks' | 'think'
  | 'tool-search' | 'tool-view' | 'tool-edit' | 'tool-fetch' | 'tool-exec' | 'tool'
  | 'summary' | 'round'
  | 'generic'

/** 判别优先级：决策 > 子智能体 > worker > plan > 综合回答 > 外部智能体 > 业务节点 > 检索 > intent > skill > tasks > think > 工具细分 > 兜底 */
export function resolveTimelineStepKind(step: ProcessingStep): TimelineStepKind {
  const id = step.id ?? ''
  const phase = step.phase
  if (isDecisionStep(step)) return 'decision'
  if (isSubagentStep(step)) return 'subagent'
  if (isWorkerStep(step)) return 'worker'
  if (isHarnessPlanStep(step)) return 'plan'
  // 综合回答（Planner-Executor 的 planner-answer 合成步）
  if (phase === 'answer' || id === 'planner-answer') return 'answer'
  // 外部智能体（A2A，phase=external / id 前缀 external-）
  if (phase === 'external' || id.startsWith('external-')) return 'external'
  // Workflow 业务节点（含 loop 轮次 i{n}-node-*；answer 节点归综合回答）
  if (phase === 'node' || id.startsWith('node-')) {
    return id === 'node-answer' ? 'answer' : 'node'
  }
  if (isRagStepId(step.id)) return 'rag'
  if (phase === 'intent') return 'intent'
  if (phase === 'skill') return 'skill'
  if (phase === 'tasks') return 'tasks'
  if (phase === 'plan') return 'plan'
  if (isThinkStepId(step.id)) return 'think'
  if (isToolStepId(step.id)) {
    const toolId = catalogToolIdFromStepId(step.id)
    // 查找文件（glob）：专属「文件夹」图标，不再并入 view 的眼睛
    if (toolId === 'sandbox__glob') return 'tool-search'
    const sandboxKind = sandboxToolKind(toolId)
    if (sandboxKind) return `tool-${sandboxKind}`
    return 'tool'
  }
  return 'generic'
}
