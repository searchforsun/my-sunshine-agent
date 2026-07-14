import type { SkillCatalogIndexEntry } from '../api/skills'
import {
  formatPlanNodeType,
  type PlanGraph,
  type PlanGraphNode,
  type PlanNodeAttempt,
  type PlanNodeTrace,
} from '../api/executionPlans'
import { resolveStepDurationMs, stepLifecycle, type ProcessingStep } from '../api/processingSteps'
import { isHitlSummaryAwaiting, relocateAgentNodeHitl, normalizePendingHitlList, reapplyPendingHitlList, type HitlConfirmationPayload } from '../api/hitlSteps'
import { isRecoveryAwaiting, isRecoverySkipped, isRecoveryTerminated, stepHasHitlAwaiting } from '../api/recoverySteps'
import { isGatewayType } from './workflowGateway'

export type DagNodeStatus = 'pending' | 'running' | 'done' | 'error' | 'awaiting_confirm' | 'paused' | 'terminated' | 'skipped'

export interface DagNodeView {
  id: string
  type: string
  label: string
  status: DagNodeStatus
  durationMs?: number
  attemptCount?: number
  /** 配置的最大重试次数（Studio 预览）；运行时以 attemptCount 优先 */
  retryMaxAttempts?: number
  attempts?: PlanNodeAttempt[]
  summary?: string
  detail?: string
  skillId?: string
  skillLabel?: string
  /** 失败且等待用户重试/终止 */
  recoveryAwaiting?: boolean
}

function isDagNode(node: PlanGraphNode): boolean {
  return node.type !== 'start'
}

function isBusinessNode(node: PlanGraphNode): boolean {
  return node.type !== 'start' && node.type !== 'answer'
}

/** 含 start / answer 的完整 DAG 顺序 */
export function fullDagOrder(graph: PlanGraph): string[] {
  const nodes = graph.nodes ?? []
  const ids = nodes.filter(isDagNode).map(n => n.id)
  const edges = graph.edges ?? []
  const hasStart = edges.some(e => e.from === 'start')
  if (edges.length === 0) {
    return hasStart ? ['start', ...ids] : ids
  }
  const incoming = new Map<string, number>()
  const adj = new Map<string, string[]>()
  for (const id of ids) {
    incoming.set(id, 0)
    adj.set(id, [])
  }
  for (const e of edges) {
    if (e.from === 'start') continue
    if (!incoming.has(e.to) || !adj.has(e.from)) continue
    adj.get(e.from)!.push(e.to)
    incoming.set(e.to, (incoming.get(e.to) ?? 0) + 1)
  }
  const queue = ids.filter(id => (incoming.get(id) ?? 0) === 0)
  const order: string[] = []
  while (queue.length > 0) {
    const cur = queue.shift()!
    order.push(cur)
    for (const next of adj.get(cur) ?? []) {
      const deg = (incoming.get(next) ?? 1) - 1
      incoming.set(next, deg)
      if (deg === 0) queue.push(next)
    }
  }
  const sorted = order.length < ids.length ? ids : order
  return hasStart ? ['start', ...sorted] : sorted
}

function nodeLabel(node: PlanGraphNode): string {
  if (node.type === 'answer') return formatPlanNodeType('answer')
  if (node.displayName?.trim()) return node.displayName.trim()
  return formatPlanNodeType(node.type)
}

/** 从 edges 拓扑排序；无边时按 nodes 数组顺序 */
export function linearNodeOrder(graph: PlanGraph): string[] {
  const nodes = (graph.nodes ?? []).filter(isBusinessNode)
  const ids = nodes.map(n => n.id)
  const edges = graph.edges ?? []
  if (edges.length === 0) return ids
  const incoming = new Map<string, number>()
  const adj = new Map<string, string[]>()
  for (const id of ids) {
    incoming.set(id, 0)
    adj.set(id, [])
  }
  for (const e of edges) {
    if (!incoming.has(e.to) || !adj.has(e.from)) continue
    adj.get(e.from)!.push(e.to)
    incoming.set(e.to, (incoming.get(e.to) ?? 0) + 1)
  }
  const queue = ids.filter(id => (incoming.get(id) ?? 0) === 0)
  const order: string[] = []
  while (queue.length > 0) {
    const cur = queue.shift()!
    order.push(cur)
    for (const next of adj.get(cur) ?? []) {
      const deg = (incoming.get(next) ?? 1) - 1
      incoming.set(next, deg)
      if (deg === 0) queue.push(next)
    }
  }
  if (order.length < ids.length) return ids
  return order
}

function mapTraceStatus(status: string): DagNodeStatus {
  if (status === 'completed') return 'done'
  if (status === 'failed') return 'error'
  if (status === 'running') return 'running'
  return 'pending'
}

function mapStepStatus(step?: ProcessingStep): DagNodeStatus {
  if (!step) return 'pending'
  if (isRecoveryAwaiting(step)) return 'error'
  if (isRecoveryTerminated(step)) return 'terminated'
  if (isRecoverySkipped(step)) return 'skipped'
  const lc = stepLifecycle(step)
  if (lc === 'paused') return 'paused'
  if (lc === 'terminated') return 'terminated'
  if (lc === 'done') return 'done'
  if (lc === 'skipped') return 'skipped'
  if (stepHasHitlAwaiting(step) || isHitlSummaryAwaiting(step)) return 'awaiting_confirm'
  if (lc === 'running') return 'running'
  if (lc === 'error') return 'error'
  return 'pending'
}

/** 合并 SSE 步与 execution_trace，刷新后 trace 可补全 node 终态 */
function statusRank(status: DagNodeStatus): number {
  switch (status) {
    case 'done': return 50
    case 'error': return 45
    case 'terminated': return 44
    case 'skipped': return 43
    case 'awaiting_confirm': return 40
    case 'paused': return 35
    case 'running': return 30
    case 'pending': return 0
    default: return 0
  }
}

function resolveNodeStatus(step?: ProcessingStep, trace?: PlanNodeTrace): DagNodeStatus {
  const fromStep = step ? mapStepStatus(step) : undefined
  const fromTrace = trace ? mapTraceStatus(trace.status) : undefined
  if (!fromStep) return fromTrace ?? 'pending'
  if (!fromTrace) return fromStep
  return statusRank(fromStep) >= statusRank(fromTrace) ? fromStep : fromTrace
}

function isTerminalStatus(status: DagNodeStatus): boolean {
  return status === 'done'
    || status === 'error'
    || status === 'skipped'
    || status === 'terminated'
}

/** start 无独立 timeline 步：任一后继节点或 plan 步已推进即视为已通过 */
function resolveStartStatus(
  order: string[],
  byId: Map<string, PlanGraphNode>,
  stepByNodeId: Map<string, ProcessingStep>,
  traceByNodeId: Map<string, PlanNodeTrace>,
  planStep?: ProcessingStep,
): DagNodeStatus {
  const planLc = planStep ? stepLifecycle(planStep) : undefined
  if (planLc === 'running' || planLc === 'done' || planLc === 'error' || planLc === 'skipped') {
    return 'done'
  }
  for (const id of order) {
    if (id === 'start') continue
    const node = byId.get(id)
    if (!node) continue
    const st = resolveNodeStatus(stepByNodeId.get(node.id), traceByNodeId.get(node.id))
    if (st !== 'pending') return 'done'
  }
  return 'pending'
}

/**
 * join / parallel-gateway 等不落 SSE 步；live 期也不刷 execution_trace。
 * 用邻居业务节点状态推断，避免等下游跑完才变绿。
 */
function resolveRoutingNodeStatus(
  node: PlanGraphNode,
  graph: PlanGraph,
  byId: Map<string, PlanGraphNode>,
  stepByNodeId: Map<string, ProcessingStep>,
  traceByNodeId: Map<string, PlanNodeTrace>,
  order: string[],
  planStep: ProcessingStep | undefined,
  base: DagNodeStatus,
): DagNodeStatus {
  if (base !== 'pending') return base
  const edges = graph.edges ?? []
  const preds = edges.filter(e => e.to === node.id).map(e => e.from)
  const succs = edges.filter(e => e.from === node.id).map(e => e.to)
  const statusOf = (id: string): DagNodeStatus => {
    if (id === 'start') {
      return resolveStartStatus(order, byId, stepByNodeId, traceByNodeId, planStep)
    }
    return resolveNodeStatus(stepByNodeId.get(id), traceByNodeId.get(id))
  }
  for (const id of succs) {
    if (statusOf(id) !== 'pending') return 'done'
  }
  if (node.type === 'join') {
    const predStatuses = preds.map(statusOf)
    if (predStatuses.length > 0 && predStatuses.every(isTerminalStatus)) return 'done'
    if (predStatuses.some(s => s !== 'pending')) return 'running'
  }
  return 'pending'
}

function stepSummary(step?: ProcessingStep): string | undefined {
  if (!step) return undefined
  return step.summary?.after?.trim()
    || step.result?.trim()
    || step.detail?.trim()
    || undefined
}

function resolveSkillLabel(skillId: string | undefined, catalog: SkillCatalogIndexEntry[]): string | undefined {
  if (!skillId?.trim()) return undefined
  const id = skillId.trim()
  const hit = catalog.find(s => s.id === id)
  return hit?.displayName?.trim() || id
}

function parseRetryMaxAttempts(params?: Record<string, string>): number | undefined {
  const raw = params?.['retry.maxAttempts']
  if (raw == null || String(raw).trim() === '') return undefined
  const n = Number(raw)
  return Number.isFinite(n) && n > 1 ? n : undefined
}

export function resolveRetryBadgeCount(node: DagNodeView): number | null {
  if (node.attemptCount != null && node.attemptCount > 1) return node.attemptCount
  return null
}

function resolveNodeAttempts(
  step?: ProcessingStep,
  trace?: PlanNodeTrace,
): PlanNodeAttempt[] | undefined {
  const fromTrace = trace?.attempts
  const fromStep = step?.metadata?.nodeAttempts
  if (fromTrace?.length && fromStep?.length) {
    return fromTrace.length >= fromStep.length ? fromTrace : fromStep
  }
  return fromTrace?.length ? fromTrace : fromStep
}

export function buildDagNodes(
  graph: PlanGraph | undefined,
  nodeSteps: ProcessingStep[],
  traces?: PlanNodeTrace[],
  skillCatalog: SkillCatalogIndexEntry[] = [],
  planStep?: ProcessingStep,
  pendingHitl?: HitlConfirmationPayload | HitlConfirmationPayload[],
): DagNodeView[] {
  if (!graph?.nodes?.length) return []
  const stepByNodeId = new Map<string, ProcessingStep>()
  for (const s of nodeSteps) {
    if (!s.id.startsWith('node-')) continue
    let step = relocateAgentNodeHitl(s)
    const pendingList = normalizePendingHitlList(pendingHitl)
    if (pendingList.length) {
      const merged = reapplyPendingHitlList([step], pendingList)
      step = merged[0] ?? step
    }
    stepByNodeId.set(s.id.slice('node-'.length), step)
  }
  const traceByNodeId = new Map<string, PlanNodeTrace>()
  for (const t of traces ?? []) {
    traceByNodeId.set(t.nodeId, t)
  }
  const byId = new Map(graph.nodes.filter(isDagNode).map(n => [n.id, n]))
  const order = fullDagOrder(graph)
  return order.flatMap(id => {
    if (id === 'start') {
      return [{
        id: 'start',
        type: 'start',
        label: '开始',
        status: resolveStartStatus(order, byId, stepByNodeId, traceByNodeId, planStep),
      }]
    }
    const node = byId.get(id)
    if (!node) return []
    const step = stepByNodeId.get(node.id)
    const trace = traceByNodeId.get(node.id)
    const baseStatus = resolveNodeStatus(step, trace)
    const isRoutingNode = isGatewayType(node.type)
    const status = isRoutingNode
      ? resolveRoutingNodeStatus(
        node, graph, byId, stepByNodeId, traceByNodeId, order, planStep, baseStatus,
      )
      : baseStatus
    const recoveryAwaiting = isRecoveryAwaiting(step)
    const durationMs = isRoutingNode
      ? undefined
      : (step
        ? resolveStepDurationMs(step)
        : (trace?.startedAt != null && trace?.endedAt != null
          ? trace.endedAt - trace.startedAt
          : undefined))
    const skillId = node.type === 'agent' ? node.params?.skill?.trim() : undefined
    const attempts = resolveNodeAttempts(step, trace)
    const retryMaxAttempts = parseRetryMaxAttempts(node.params)
    return [{
      id: node.id,
      type: node.type,
      label: nodeLabel(node),
      status,
      durationMs,
      attemptCount: trace?.attemptCount ?? attempts?.length,
      retryMaxAttempts,
      attempts,
      summary: (stepSummary(step) ?? trace?.summary?.trim()) || undefined,
      detail: step?.detail?.trim() || step?.result?.trim() || trace?.detail?.trim() || undefined,
      skillId,
      skillLabel: resolveSkillLabel(skillId, skillCatalog),
      recoveryAwaiting: recoveryAwaiting ? true : undefined,
    }]
  })
}
