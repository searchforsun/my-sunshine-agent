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

/**
 * exclusive-gateway 已路由后，未走的出边及仅经跳过边可达的节点标为 skipped，
 * 避免终态仍显示「等待中」。
 */
function markUntakenExclusiveBranches(
  graph: PlanGraph,
  statusById: Map<string, DagNodeStatus>,
): void {
  const edges = graph.edges ?? []
  const nodes = graph.nodes ?? []
  for (const node of nodes) {
    if (node.type !== 'exclusive-gateway') continue
    const gw = statusById.get(node.id)
    if (gw !== 'done' && gw !== 'running') continue
    const succs = edges.filter(e => e.from === node.id).map(e => e.to)
    const routed = gw === 'done' || succs.some(id => {
      const st = statusById.get(id)
      return st != null && st !== 'pending'
    })
    if (!routed) continue
    for (const id of succs) {
      if (statusById.get(id) === 'pending') {
        statusById.set(id, 'skipped')
      }
    }
  }
  let changed = true
  while (changed) {
    changed = false
    for (const node of nodes) {
      if (node.type === 'start' || node.type === 'answer') continue
      if (statusById.get(node.id) !== 'pending') continue
      const preds = edges.filter(e => e.to === node.id).map(e => e.from)
      if (preds.length === 0) continue
      if (preds.every(id => id !== 'start' && statusById.get(id) === 'skipped')) {
        statusById.set(node.id, 'skipped')
        changed = true
      }
    }
  }
}

/** loop.subSteps id：i{n}-node-{bodyId} → 取最大轮次对应子步 */
export function findLoopBodySubStep(
  loopStep: ProcessingStep | undefined,
  bodyNodeId: string,
): ProcessingStep | undefined {
  const subs = loopStep?.subSteps
  if (!subs?.length) return undefined
  const suffix = `node-${bodyNodeId}`
  let best: ProcessingStep | undefined
  let bestRound = -1
  for (const sub of subs) {
    const id = sub.id ?? ''
    const m = /^i(\d+)-(.*)$/.exec(id)
    if (!m || m[2] !== suffix) continue
    const round = Number(m[1])
    if (round >= bestRound) {
      bestRound = round
      best = sub
    }
  }
  return best
}

/**
 * DAG 节点 → Timeline 步：顶层 node-{id}；loop 框内读 parent.subSteps 的 i{n}-node-{id}。
 */
export function resolveDagNodeStep(
  nodeId: string,
  steps: ProcessingStep[] | undefined,
  graph?: PlanGraph | null,
  planStep?: ProcessingStep,
): ProcessingStep | undefined {
  if (nodeId === 'start') return planStep
  const top = steps?.find(s => s.id === `node-${nodeId}`)
  if (top) return relocateAgentNodeHitl(top)
  const parentId = graph?.nodes?.find(n => n.id === nodeId)?.parentId
  if (!parentId || !steps?.length) return undefined
  const loopStep = steps.find(s => s.id === `node-${parentId}`)
  const body = findLoopBodySubStep(loopStep, nodeId)
  return body ? relocateAgentNodeHitl(body) : undefined
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
  const statusById = new Map<string, DagNodeStatus>()
  const draft = order.flatMap(id => {
    if (id === 'start') {
      const status = resolveStartStatus(order, byId, stepByNodeId, traceByNodeId, planStep)
      statusById.set('start', status)
      return [{
        id: 'start',
        type: 'start',
        label: '开始',
        status,
      }]
    }
    const node = byId.get(id)
    if (!node) return []
    const step = stepByNodeId.get(node.id)
    const trace = traceByNodeId.get(node.id)
    const baseStatus = resolveNodeStatus(step, trace)
    const isRoutingNode = isGatewayType(node.type)
    let status = isRoutingNode
      ? resolveRoutingNodeStatus(
        node, graph, byId, stepByNodeId, traceByNodeId, order, planStep, baseStatus,
      )
      : baseStatus
    let durationMs = isRoutingNode
      ? undefined
      : (step
        ? resolveStepDurationMs(step)
        : (trace?.startedAt != null && trace?.endedAt != null
          ? trace.endedAt - trace.startedAt
          : undefined))
    // loop 框内：状态/耗时读 parent.subSteps（i{n}-node-*）；无子步且父已终态才「已跳过」
    if (status === 'pending' && node.parentId) {
      const parentStep = stepByNodeId.get(node.parentId)
      const bodySub = findLoopBodySubStep(parentStep, node.id)
      if (bodySub) {
        status = mapStepStatus(bodySub)
        durationMs = resolveStepDurationMs(bodySub) ?? durationMs
      } else {
        const parentSt = resolveNodeStatus(parentStep, traceByNodeId.get(node.parentId))
        if (parentSt === 'running' || parentSt === 'paused' || parentSt === 'awaiting_confirm') {
          status = 'pending'
        } else if (parentSt === 'done' || parentSt === 'error' || parentSt === 'skipped' || parentSt === 'terminated') {
          status = 'skipped'
        }
      }
    }
    statusById.set(node.id, status)
    const recoveryAwaiting = isRecoveryAwaiting(step)
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
  markUntakenExclusiveBranches(graph, statusById)
  return draft.map(node => {
    const next = statusById.get(node.id)
    return next != null && next !== node.status ? { ...node, status: next } : node
  })
}
