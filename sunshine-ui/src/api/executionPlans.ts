import { apiHeaders } from '../stores/authStore'
import { BFF_API_BASE } from './config'

export interface PlanNodeTrace {
  nodeId: string
  type: string
  status: string
  summary?: string
  detail?: string
  startedAt?: number
  endedAt?: number
}

export interface PlanGraphNode {
  id: string
  type: string
  displayName?: string
  params?: Record<string, string>
}

export interface PlanGraphEdge {
  from: string
  to: string
}

export interface PlanGraph {
  planId?: string | null
  reason?: string
  nodes?: PlanGraphNode[]
  edges?: PlanGraphEdge[]
}

export interface ExecutionPlanDetail {
  id: string
  conversationId: string
  messageId: string
  status: string
  plannerModel?: string
  plannerReason?: string
  rejectReason?: string
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
  plannerReason?: string
  createdAt?: string
  completedAt?: string
}

async function parseJson<T>(res: Response): Promise<T> {
  const raw: unknown = await res.json()
  if (!res.ok) {
    const msg = typeof raw === 'object' && raw && 'msg' in raw
      ? String((raw as { msg: string }).msg)
      : `HTTP ${res.status}`
    throw new Error(msg)
  }
  if (typeof raw === 'object' && raw && 'code' in raw && 'msg' in raw) {
    const wrapped = raw as { code: number; msg: string; data: T }
    if (wrapped.code !== 200) throw new Error(wrapped.msg || '请求失败')
    return wrapped.data
  }
  return raw as T
}

function apiUrl(path: string): string {
  return `${BFF_API_BASE}${path}`
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
    draft: '草稿',
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
    answer: '汇总输出',
    start: '开始',
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
