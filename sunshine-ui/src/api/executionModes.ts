/** Chat 底栏执行模式 — 与后端 ExecutionPreference / ExecutionMode 对齐 */

export type ExecutionPreference =
  | 'auto'
  | 'react'
  | 'workflow'
  | 'plan-workflow'
  | 'peer-collab'

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
    value: 'auto',
    label: '自动',
    shortLabel: '自动',
    description: '根据提问意图自动选择执行方式',
    allowsSkillMention: true,
    allowsAgentMention: true,
    allowsWorkflowMention: true,
  },
  {
    value: 'react',
    label: '自主推理',
    shortLabel: '推理',
    description: 'ReAct 多工具自主分析',
    allowsSkillMention: true,
    allowsAgentMention: false,
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
  {
    value: 'plan-workflow',
    label: '动态规划',
    shortLabel: '规划',
    description: 'Planner 动态编排多步 DAG',
    allowsSkillMention: true,
    allowsAgentMention: false,
    allowsWorkflowMention: false,
  },
  {
    value: 'peer-collab',
    label: '多专家协作',
    shortLabel: '协作',
    description: '多位智能体对等讨论后引擎汇总作答',
    allowsSkillMention: false,
    allowsAgentMention: true,
    allowsWorkflowMention: false,
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

export function isExecutionPreference(raw: unknown): raw is ExecutionPreference {
  return raw === 'auto'
    || raw === 'react'
    || raw === 'workflow'
    || raw === 'plan-workflow'
    || raw === 'peer-collab'
}
