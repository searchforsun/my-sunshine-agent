import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseBffPayload } from './apiError'

export interface PlanNodeTrace {
  nodeId: string
  type: string
  status: string
  summary?: string
  detail?: string
  startedAt?: number
  endedAt?: number
  attemptCount?: number
  onFailure?: string
  attempts?: PlanNodeAttempt[]
}

export interface PlanNodeAttempt {
  attemptNo: number
  status: string
  errorClass?: string
  summary?: string
  startedAt?: number
  endedAt?: number
}

export interface PlanGraphNode {
  id: string
  type: string
  displayName?: string
  params?: Record<string, string>
  /** loop 框内 body 归属 */
  parentId?: string
}

export interface PlanGraphEdge {
  from: string
  to: string
  condition?: {
    logic: 'and' | 'or'
    items: { left: string; op: string; right?: string }[]
  }
  default?: boolean
}

export interface PlanGraph {
  planId?: string | null
  reason?: string
  nodes?: PlanGraphNode[]
  edges?: PlanGraphEdge[]
  /** Studio 画布坐标快照（BPMN DI 等价；loop 可带 width/height） */
  layout?: Record<string, { x: number; y: number; width?: number; height?: number }>
}

export interface ExecutionPlanDetail {
  id: string
  conversationId: string
  messageId: string
  status: string
  plan: PlanGraph
  validatedPlan?: PlanGraph
  nodes: PlanNodeTrace[]
  createdAt?: string
  validatedAt?: string
  startedAt?: string
  completedAt?: string
}

export interface ExecutionPlanSummary {
  id: string
  messageId: string
  status: string
  createdAt?: string
  completedAt?: string
}

async function parseJson<T>(res: Response): Promise<T> {
  return parseBffPayload<T>(res)
}

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export async function getExecutionPlan(planId: string): Promise<ExecutionPlanDetail> {
  const res = await fetch(apiUrl(`/api/execution-plans/${encodeURIComponent(planId)}`), {
    headers: apiHeaders(),
  })
  return parseJson<ExecutionPlanDetail>(res)
}

export async function listExecutionPlans(conversationId: string): Promise<ExecutionPlanSummary[]> {
  const res = await fetch(
    apiUrl(`/api/execution-plans?conversationId=${encodeURIComponent(conversationId)}`),
    { headers: apiHeaders() },
  )
  return parseJson<ExecutionPlanSummary[]>(res)
}

export async function getExecutionPlanNodes(planId: string): Promise<PlanNodeTrace[]> {
  const res = await fetch(apiUrl(`/api/execution-plans/${encodeURIComponent(planId)}/nodes`), {
    headers: apiHeaders(),
  })
  return parseJson<PlanNodeTrace[]>(res)
}

export function formatPlanStatus(status: string): string {
  const map: Record<string, string> = {
    awaiting_approval: '待确认',
    validated: '已校验',
    running: '执行中',
    completed: '已完成',
    failed: '失败',
    rejected: '已拒绝',
  }
  return map[status] ?? status
}

export function formatPlanNodeType(type: string): string {
  const map: Record<string, string> = {
    rag: '知识检索',
    tool: '工具调用',
    llm: '综合分析',
    agent: '子 Agent',
    worker: '执行单元',
    join: '并行汇总',
    'parallel-gateway': '并行分叉',
    'exclusive-gateway': '条件分支',
    loop: '循环',
    'variable-assignment': '变量赋值',
    'parameter-extractor': '参数提取',
    answer: '回答',
    start: '开始',
    task: '任务',
  }
  return map[type] ?? type
}

export function formatTraceStatus(status: string): string {
  const map: Record<string, string> = {
    completed: '完成',
    failed: '失败',
    running: '执行中',
  }
  return map[status] ?? status
}
