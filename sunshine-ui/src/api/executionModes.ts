/** Chat 底栏执行模式 — 与后端 ExecutionMode 对齐（routing v6） */

export type ExecutionMode = 'fast' | 'pro' | 'workflow'

export interface ExecutionModeOption {
  value: ExecutionMode
  label: string
  shortLabel: string
  description: string
  allowsSkillMention: boolean
  allowsAgentMention: boolean
  allowsWorkflowMention: boolean
}

export const EXECUTION_MODE_OPTIONS: ExecutionModeOption[] = [
  {
    value: 'fast',
    label: '快速',
    shortLabel: '快速',
    description: 'ReAct 多工具自主分析，可委派子智能体',
    allowsSkillMention: true,
    allowsAgentMention: true,
    allowsWorkflowMention: false,
  },
  {
    value: 'pro',
    label: '专业',
    shortLabel: '专业',
    description: 'Planner-Executor 规划并执行复杂任务',
    allowsSkillMention: true,
    allowsAgentMention: true,
    allowsWorkflowMention: false,
  },
  {
    value: 'workflow',
    label: '工作流',
    shortLabel: '流程',
    description: '按预置 workflow 模板执行',
    allowsSkillMention: false,
    allowsAgentMention: false,
    allowsWorkflowMention: true,
  },
]

export function findExecutionModeOption(value: ExecutionMode): ExecutionModeOption {
  return EXECUTION_MODE_OPTIONS.find(o => o.value === value) ?? EXECUTION_MODE_OPTIONS[0]
}

export function allowsSkillMention(preference: ExecutionMode): boolean {
  return findExecutionModeOption(preference).allowsSkillMention
}

export function allowsAgentMention(preference: ExecutionMode): boolean {
  return findExecutionModeOption(preference).allowsAgentMention
}

export function allowsWorkflowMention(preference: ExecutionMode): boolean {
  return findExecutionModeOption(preference).allowsWorkflowMention
}

export const EXECUTION_PREFERENCE_STORAGE_KEY = 'sunshine-execution-preference'

/** 新 wire 三值；旧 localStorage / API 值经 normalize 映射 */
export function isExecutionMode(raw: unknown): raw is ExecutionMode {
  return raw === 'fast' || raw === 'pro' || raw === 'workflow'
}

/** 读路径仅认协议三值；未知/旧值一律回退 fast */
export function normalizeExecutionMode(raw: unknown): ExecutionMode {
  if (isExecutionMode(raw)) return raw
  return 'fast'
}
