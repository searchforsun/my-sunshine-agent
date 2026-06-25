/** Chat 底栏执行模式 — 与后端 ExecutionPreference / ExecutionMode 对齐 */

export type ExecutionPreference =
  | 'auto'
  | 'simple-llm'
  | 'react'
  | 'workflow'
  | 'plan-workflow'

export interface ExecutionModeOption {
  value: ExecutionPreference
  label: string
  shortLabel: string
  description: string
  allowsSkillMention: boolean
}

export const EXECUTION_MODE_OPTIONS: ExecutionModeOption[] = [
  {
    value: 'auto',
    label: '自动',
    shortLabel: '自动',
    description: '根据提问意图自动选择执行方式',
    allowsSkillMention: true,
  },
  {
    value: 'simple-llm',
    label: '简单对话',
    shortLabel: '简单',
    description: '单轮直答，不走企业知识库与工具',
    allowsSkillMention: false,
  },
  {
    value: 'react',
    label: '自主推理',
    shortLabel: '推理',
    description: 'ReAct 多工具自主分析',
    allowsSkillMention: true,
  },
  {
    value: 'workflow',
    label: '工作流',
    shortLabel: '流程',
    description: '按预置 workflow 模板执行',
    allowsSkillMention: false,
  },
  {
    value: 'plan-workflow',
    label: '动态规划',
    shortLabel: '规划',
    description: 'Planner 动态编排多步 DAG',
    allowsSkillMention: true,
  },
]

export function findExecutionModeOption(value: ExecutionPreference): ExecutionModeOption {
  return EXECUTION_MODE_OPTIONS.find(o => o.value === value) ?? EXECUTION_MODE_OPTIONS[0]
}

export function allowsSkillMention(preference: ExecutionPreference): boolean {
  return findExecutionModeOption(preference).allowsSkillMention
}

export const EXECUTION_PREFERENCE_STORAGE_KEY = 'sunshine-execution-preference'

export function isExecutionPreference(raw: unknown): raw is ExecutionPreference {
  return raw === 'auto'
    || raw === 'simple-llm'
    || raw === 'react'
    || raw === 'workflow'
    || raw === 'plan-workflow'
}
