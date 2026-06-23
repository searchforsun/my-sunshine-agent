import type { SkillCatalogIndexEntry } from '../api/skills'
import {
  formatPlanNodeType,
  type PlanGraph,
  type PlanGraphNode,
  type PlanNodeTrace,
} from '../api/executionPlans'
import { resolveStepDurationMs, stepLifecycle } from '../api/processingSteps'

export type DagNodeStatus = 'pending' | 'running' | 'done' | 'error'

export interface DagNodeView {
  id: string
  type: string
  label: string
  status: DagNodeStatus
  durationMs?: number
  summary?: string
  detail?: string
  skillId?: string
  skillLabel?: string
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
  const lc = stepLifecycle(step)
  if (lc === 'running') return 'running'
  if (lc === 'error') return 'error'
  if (lc === 'done' || lc === 'skipped') return 'done'
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

export function buildDagNodes(
  graph: PlanGraph | undefined,
  nodeSteps: ProcessingStep[],
  traces?: PlanNodeTrace[],
  skillCatalog: SkillCatalogIndexEntry[] = [],
): DagNodeView[] {
  if (!graph?.nodes?.length) return []
  const stepByNodeId = new Map<string, ProcessingStep>()
  for (const s of nodeSteps) {
    if (s.id.startsWith('node-')) {
      stepByNodeId.set(s.id.slice('node-'.length), s)
    }
  }
  const traceByNodeId = new Map<string, PlanNodeTrace>()
  for (const t of traces ?? []) {
    traceByNodeId.set(t.nodeId, t)
  }
  const byId = new Map(graph.nodes.filter(isDagNode).map(n => [n.id, n]))
  const order = fullDagOrder(graph)
  return order.map(id => {
    if (id === 'start') {
      const firstBiz = order.find(x => x !== 'start' && byId.has(x))
      const firstStep = firstBiz ? stepByNodeId.get(firstBiz) : undefined
      const status = firstStep ? mapStepStatus(firstStep) : 'pending'
      return {
        id: 'start',
        type: 'start',
        label: '开始',
        status: status === 'pending' ? 'pending' : 'done',
      }
    }
    const node = byId.get(id)
    if (!node) return null
    const step = stepByNodeId.get(node.id)
    const trace = traceByNodeId.get(node.id)
    const status = step ? mapStepStatus(step) : mapTraceStatus(trace?.status ?? 'pending')
    const durationMs = step
      ? resolveStepDurationMs(step)
      : (trace?.startedAt != null && trace?.endedAt != null
        ? trace.endedAt - trace.startedAt
        : undefined)
    const skillId = node.type === 'agent' ? node.params?.skill?.trim() : undefined
    return {
      id: node.id,
      type: node.type,
      label: nodeLabel(node),
      status,
      durationMs,
      summary: (stepSummary(step) ?? trace?.summary?.trim()) || undefined,
      detail: step?.detail?.trim() || step?.result?.trim() || trace?.detail?.trim() || undefined,
      skillId,
      skillLabel: resolveSkillLabel(skillId, skillCatalog),
    }
  }).filter((n): n is DagNodeView => !!n)
}
