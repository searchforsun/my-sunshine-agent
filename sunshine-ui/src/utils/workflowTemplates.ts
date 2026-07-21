import type { WorkflowPlan, WorkflowNodeDefaultsResponse } from '../api/workflows'
import { autoLayoutPlan } from './workflowDagLayout'
import { applyPlanDefaults } from './workflowNodeParams'
import {
  buildExclusiveBranchRagPlan,
  buildFinanceListPlan,
  buildFinanceSummaryPlan,
  buildLinearRagQaPlan,
  buildLinearToolAgentPlan,
  buildParallelDualRagPlan,
} from './workflowPlan'

export type WorkflowTemplateId =
  | 'linear-rag-qa'
  | 'linear-tool-agent'
  | 'parallel-dual-rag'
  | 'exclusive-branch-rag'
  | 'finance-list'
  | 'finance-summary'

export type WorkflowTemplateContext = {
  isParallel: boolean
}

export type WorkflowTemplateDefinition = {
  id: WorkflowTemplateId
  name: string
  summary: string
  disabledReason?: (ctx: WorkflowTemplateContext) => string | undefined
}

export const WORKFLOW_TEMPLATES: WorkflowTemplateDefinition[] = [
  {
    id: 'linear-rag-qa',
    name: '知识库问答',
    summary: 'start → RAG 检索 → answer，适用于制度/流程类问答',
  },
  {
    id: 'linear-tool-agent',
    name: '工具 + Agent',
    summary: 'start → Tool 查数 → Agent 分析 → answer，适用于待办查询后智能分析',
  },
  {
    id: 'finance-list',
    name: '我的报销查询',
    summary: 'start → Tool 列出当前用户报销单 → answer',
  },
  {
    id: 'finance-summary',
    name: '报销汇总统计',
    summary: 'start → Tool 按状态汇总报销条数与金额 → answer',
  },
  {
    id: 'parallel-dual-rag',
    name: '并行双检索',
    summary: '两路 RAG 并行检索后 join 汇总回答',
    disabledReason: ctx => (ctx.isParallel ? '当前已是并行 DAG，无需重复应用' : undefined),
  },
  {
    id: 'exclusive-branch-rag',
    name: '条件分支检索',
    summary: 'exclusive-gateway：含「报销」走财务 RAG，否则默认人事 RAG',
  },
]

export function getWorkflowTemplate(id: WorkflowTemplateId): WorkflowTemplateDefinition | undefined {
  return WORKFLOW_TEMPLATES.find(t => t.id === id)
}

export function buildWorkflowTemplatePreviewPlan(
  id: WorkflowTemplateId,
  nodeDefaults?: WorkflowNodeDefaultsResponse | null,
): WorkflowPlan {
  const previewId = '__template_preview__'
  const resolved = nodeDefaults ?? undefined
  let plan: WorkflowPlan
  switch (id) {
    case 'linear-rag-qa':
      plan = autoLayoutPlan(buildLinearRagQaPlan(previewId, resolved))
      break
    case 'linear-tool-agent':
      plan = autoLayoutPlan(buildLinearToolAgentPlan(previewId, resolved))
      break
    case 'finance-list':
      plan = autoLayoutPlan(buildFinanceListPlan(previewId, resolved))
      break
    case 'finance-summary':
      plan = autoLayoutPlan(buildFinanceSummaryPlan(previewId, resolved))
      break
    case 'parallel-dual-rag':
      plan = autoLayoutPlan(buildParallelDualRagPlan(previewId, resolved))
      break
    case 'exclusive-branch-rag':
      plan = autoLayoutPlan(buildExclusiveBranchRagPlan(previewId, resolved))
      break
    default:
      throw new Error(`Unknown workflow template: ${id}`)
  }
  return applyPlanDefaults(plan, resolved)
}
