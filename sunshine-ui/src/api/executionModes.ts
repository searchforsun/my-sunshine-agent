/** Chat 底栏执行模式 — 与后端 ExecutionPreference / ExecutionMode 对齐（routing v6） */

export type ExecutionPreference = 'fast' | 'pro' | 'workflow'

export interface ExecutionModeOption {
  value: ExecutionPreference
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

export function findExecutionModeOption(value: ExecutionPreference): ExecutionModeOption {
  return EXECUTION_MODE_OPTIONS.find(o => o.value === value) ?? EXECUTION_MODE_OPTIONS[0]
}

export function allowsSkillMention(preference: ExecutionPreference): boolean {
  return findExecutionModeOption(preference).allowsSkillMention
}

export function allowsAgentMention(preference: ExecutionPreference): boolean {
  return findExecutionModeOption(preference).allowsAgentMention
}

export function allowsWorkflowMention(preference: ExecutionPreference): boolean {
  return findExecutionModeOption(preference).allowsWorkflowMention
}

export const EXECUTION_PREFERENCE_STORAGE_KEY = 'sunshine-execution-preference'

/** 新 wire 三值；旧 localStorage / API 值经 normalize 映射 */
export function isExecutionPreference(raw: unknown): raw is ExecutionPreference {
  return raw === 'fast' || raw === 'pro' || raw === 'workflow'
}

/** 读路径兼容：auto/react→fast，plan-workflow→pro；未知回退 fast */
export function normalizeExecutionPreference(raw: unknown): ExecutionPreference {
  if (raw === 'pro' || raw === 'plan-workflow') return 'pro'
  if (raw === 'workflow') return 'workflow'
  if (raw === 'fast' || raw === 'auto' || raw === 'react') return 'fast'
  return 'fast'
}
