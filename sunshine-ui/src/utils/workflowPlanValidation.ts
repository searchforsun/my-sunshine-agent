import type { WorkflowPlan } from '../api/workflows'

export type ConnectionEval = { ok: true } | { ok: false; reason: string }

function buildAdjacency(edges: { from: string; to: string }[]): Map<string, string[]> {
  const out = new Map<string, string[]>()
  for (const e of edges) {
    out.set(e.from, [...(out.get(e.from) ?? []), e.to])
  }
  return out
}

function buildIncoming(edges: { from: string; to: string }[]): Map<string, string[]> {
  const incoming = new Map<string, string[]>()
  for (const e of edges) {
    incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  }
  return incoming
}

/** 新增 from→to 后是否形成环 */
export function wouldCreateCycle(
  edges: { from: string; to: string }[],
  from: string,
  to: string,
): boolean {
  const out = buildAdjacency([...edges, { from, to }])
  const stack = [to]
  const seen = new Set<string>()
  while (stack.length > 0) {
    const cur = stack.pop()!
    if (cur === from) return true
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const next of out.get(cur) ?? []) {
      stack.push(next)
    }
  }
  return false
}

export function evaluateConnection(
  connection: { source?: string | null; target?: string | null },
  plan: WorkflowPlan,
): ConnectionEval {
  const source = connection.source ?? ''
  const target = connection.target ?? ''
  if (!source || !target) {
    return { ok: false, reason: '连线不完整' }
  }
  if (source === target) {
    return { ok: false, reason: '不能连接到自身' }
  }
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const sourceNode = nodes.find(n => n.id === source)
  const targetNode = nodes.find(n => n.id === target)
  if (!sourceNode || !targetNode) {
    return { ok: false, reason: '连线引用了不存在的节点' }
  }
  if (sourceNode.type === 'answer') {
    return { ok: false, reason: '结束节点不能作为连线的起点' }
  }
  if (targetNode.type === 'start') {
    return { ok: false, reason: '开始节点不能作为连线的终点' }
  }
  if (edges.some(e => e.from === source && e.to === target)) {
    return { ok: false, reason: '该连线已存在' }
  }
  const sourceOut = edges.filter(e => e.from === source)
  if (sourceNode.type === 'join' && sourceOut.length >= 1) {
    return { ok: false, reason: '汇总节点只能有一条出边' }
  }
  if (wouldCreateCycle(edges, source, target)) {
    return { ok: false, reason: '此连线将形成环，请调整拓扑' }
  }
  return { ok: true }
}

export function isValidConnection(
  connection: { source?: string | null; target?: string | null },
  plan: WorkflowPlan,
): boolean {
  return evaluateConnection(connection, plan).ok
}

export function countNodeDegree(plan: WorkflowPlan, nodeId: string): { in: number; out: number } {
  const edges = plan.edges ?? []
  return {
    in: edges.filter(e => e.to === nodeId).length,
    out: edges.filter(e => e.from === nodeId).length,
  }
}

/** 识别 join 的公共分叉点（与 orchestrator PlanExecutionSchedule 同构） */
export function findJoinForkPoint(plan: WorkflowPlan, joinId: string): string | null {
  const edges = plan.edges ?? []
  const incoming = buildIncoming(edges)
  const preds = incoming.get(joinId) ?? []
  if (preds.length === 0) return null
  let candidates = new Set(incoming.get(preds[0]) ?? [])
  for (let i = 1; i < preds.length; i++) {
    const next = new Set(incoming.get(preds[i]) ?? [])
    candidates = new Set([...candidates].filter(id => next.has(id)))
  }
  if (candidates.size === 0) return null
  return [...candidates].sort()[0]
}

/** 从校验文案提取相关节点 id，供画布高亮 */
export function extractValidationIssueNodeIds(issues: string[]): Set<string> {
  const ids = new Set<string>()
  for (const issue of issues) {
    for (const m of issue.matchAll(/「([\w-]+)」/g)) {
      ids.add(m[1])
    }
    const nodeMatch = issue.match(/节点\s+([\w-]+)\s/)
    if (nodeMatch) ids.add(nodeMatch[1])
    for (const m of issue.matchAll(/(?:from|to)=([\w-]+)/g)) {
      ids.add(m[1])
    }
  }
  return ids
}

/** Studio 画布内快速拓扑提示（发布仍以服务端 PlanValidator 为准） */
export function validatePlanTopologyLocally(plan: WorkflowPlan): string[] {
  const issues: string[] = []
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const types = new Map(nodes.map(n => [n.id, n.type]))
  const incoming = buildIncoming(edges)
  const outgoing = buildAdjacency(edges)
  for (const [id, type] of types) {
    if (type === 'join') {
      const preds = incoming.get(id) ?? []
      if (preds.length < 2) {
        issues.push(`汇总节点「${id}」入度须 ≥ 2（并行分支汇入）`)
      }
      const succs = outgoing.get(id) ?? []
      if (succs.length !== 1) {
        issues.push(`汇总节点「${id}」出度须为 1`)
      }
    }
  }
  if (hasCycle(edges)) {
    issues.push('DAG 存在环路，请删除或调整连线')
  }
  return issues
}

function hasCycle(edges: { from: string; to: string }[]): boolean {
  const nodes = new Set<string>()
  for (const e of edges) {
    nodes.add(e.from)
    nodes.add(e.to)
  }
  const out = buildAdjacency(edges)
  const visiting = new Set<string>()
  const done = new Set<string>()
  function dfs(id: string): boolean {
    if (done.has(id)) return false
    if (visiting.has(id)) return true
    visiting.add(id)
    for (const next of out.get(id) ?? []) {
      if (dfs(next)) return true
    }
    visiting.delete(id)
    done.add(id)
    return false
  }
  for (const id of nodes) {
    if (dfs(id)) return true
  }
  return false
}
