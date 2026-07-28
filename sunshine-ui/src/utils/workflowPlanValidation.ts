import type { WorkflowPlan } from '../api/workflows'
import {
  isExclusiveGateway,
  isLoopType,
  isParallelForkGateway,
  isParallelMergeGateway,
} from './workflowGateway'

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
  const fromBody = !!sourceNode.parentId
  const toBody = !!targetNode.parentId
  if (fromBody !== toBody) {
    return { ok: false, reason: '不能跨循环容器连线' }
  }
  if (fromBody && toBody && sourceNode.parentId !== targetNode.parentId) {
    return { ok: false, reason: '不能跨不同循环容器连线' }
  }
  if (fromBody || toBody) {
    const bodyTypeOk = (t: string) => ['rag', 'tool', 'agent'].includes(t)
    if (!bodyTypeOk(sourceNode.type) || !bodyTypeOk(targetNode.type)) {
      return { ok: false, reason: '循环框内仅允许 rag / tool / agent' }
    }
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

/** 识别 join 的并行分叉点（优先 BPMN parallel-gateway） */
export function findJoinForkPoint(plan: WorkflowPlan, joinId: string): string | null {
  const edges = plan.edges ?? []
  const nodes = plan.nodes ?? []
  const typeById = new Map(nodes.map(n => [n.id, n.type]))
  const incoming = buildIncoming(edges)
  const preds = incoming.get(joinId) ?? []
  if (preds.length === 0) return null
  let candidates = new Set(incoming.get(preds[0]) ?? [])
  for (let i = 1; i < preds.length; i++) {
    const next = new Set(incoming.get(preds[i]) ?? [])
    candidates = new Set([...candidates].filter(id => next.has(id)))
  }
  if (candidates.size === 0) return null
  const sorted = [...candidates].sort()
  const pg = sorted.find(id => isParallelForkGateway(typeById.get(id)))
  if (pg) return pg
  return sorted[0] ?? null
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

/** Studio 画布零延迟拓扑提示（连线/并行/exclusive/loop）；发布权威为服务端 WorkflowPlanValidator */
export function validatePlanTopologyLocally(plan: WorkflowPlan): string[] {
  const issues: string[] = []
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const types = new Map(nodes.map(n => [n.id, n.type]))
  const incoming = buildIncoming(edges)
  const outgoing = buildAdjacency(edges)
  for (const [id, type] of types) {
    if (isParallelMergeGateway(type)) {
      const preds = incoming.get(id) ?? []
      if (preds.length < 2) {
        issues.push(`并行汇总「${id}」入度须 ≥ 2（多路汇入）`)
      }
      const succs = outgoing.get(id) ?? []
      if (succs.length !== 1) {
        issues.push(`并行汇总「${id}」出度须为 1`)
      }
    }
    if (isParallelForkGateway(type)) {
      const succs = outgoing.get(id) ?? []
      if (succs.length < 2) {
        issues.push(`并行分叉「${id}」出度须 ≥ 2（至少两条并行分支）`)
      }
    }
    if (isExclusiveGateway(type)) {
      const succs = outgoing.get(id) ?? []
      if (succs.length < 2) {
        issues.push(`条件分支「${id}」出度须 ≥ 2（至少两条互斥分支）`)
      }
      const outEdges = edges.filter(e => e.from === id)
      const defaults = outEdges.filter(e => e.default)
      if (outEdges.length >= 2 && defaults.length !== 1) {
        issues.push(`条件分支「${id}」须恰好 1 条默认出边`)
      }
      for (const e of outEdges) {
        if (e.default) continue
        if (e.condition && 'items' in e.condition) {
          if (e.condition.items.length === 0) {
            issues.push(`条件分支出边 ${e.from}->${e.to} 须配置条件或标为默认`)
          }
          continue
        }
        const op = e.condition?.op?.trim()
        const left = e.condition?.left?.trim()
        if (!op || !left) {
          issues.push(`条件分支出边 ${e.from}→${e.to} 须配置条件或标为默认`)
        }
      }
    }
    if (isLoopType(type)) {
      const body = nodes.filter(n => n.parentId === id)
      if (body.length === 0) {
        issues.push(`循环「${id}」须包含至少一个框内节点`)
      }
      const succs = outgoing.get(id) ?? []
      if (succs.length !== 1) {
        issues.push(`循环「${id}」外图出度须为 1`)
      }
      const params = nodes.find(n => n.id === id)?.params ?? {}
      const op = String(params['condition.op'] ?? '').trim()
      const left = String(params['condition.left'] ?? '').trim()
      if (!op || !left) {
        issues.push(`循环「${id}」须配置条件算子与左值`)
      }
      const maxRaw = String(params['maxIterations'] ?? '3')
      const max = Number(maxRaw)
      if (!Number.isFinite(max) || max < 1 || max > 5) {
        issues.push(`循环「${id}」的 maxIterations 须在 1–5`)
      }
    }
  }
  for (const n of nodes) {
    if (!n.parentId) continue
    const parent = nodes.find(p => p.id === n.parentId)
    if (!parent || parent.type !== 'loop') {
      issues.push(`节点「${n.id}」的 parentId 须指向 loop 容器`)
    }
    if (!['rag', 'tool', 'agent'].includes(n.type)) {
      issues.push(`循环框内节点「${n.id}」类型须为 rag/tool/agent`)
    }
  }
  for (const e of edges) {
    const from = nodes.find(n => n.id === e.from)
    const to = nodes.find(n => n.id === e.to)
    const fromBody = !!from?.parentId
    const toBody = !!to?.parentId
    if (fromBody !== toBody) {
      issues.push(`禁止跨框边 ${e.from}→${e.to}`)
    }
  }
  for (const e of edges) {
    if (!e.default && !e.condition) continue
    const fromType = nodes.find(n => n.id === e.from)?.type
    if (!isExclusiveGateway(fromType)) {
      issues.push(`边 ${e.from}→${e.to} 的条件/默认仅允许条件分支出边`)
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
