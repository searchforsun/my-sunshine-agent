import type { Edge, Node } from '@vue-flow/core'
import { Position } from '@vue-flow/core'
import type { WorkflowPlan, WorkflowPlanNode, WorkflowNodeDefaultsResponse } from '../api/workflows'
import {
  defaultDisplayName,
  defaultParamsForType,
  nextBusinessNodeId,
  reconcilePlanDataFlow,
  rebuildLinearEdges,
  type WorkflowBusinessNodeType,
} from './workflowPlan'
import { buildRetryParams, resolveNodeDefaults } from './workflowNodeParams'
import { findJoinForkPoint } from './workflowPlanValidation'

export { evaluateConnection, isValidConnection } from './workflowPlanValidation'

export const WF_FLOW_NODE_TYPE = 'workflowNode'

/** 流程锚点节点，画布不可删除 */
export const PROTECTED_WORKFLOW_NODE_IDS = new Set(['start', 'answer'])

export function isProtectedWorkflowNode(node: Pick<WorkflowPlanNode, 'id' | 'type'>): boolean {
  return node.type === 'start' || node.type === 'answer' || PROTECTED_WORKFLOW_NODE_IDS.has(node.id)
}

export type WorkflowFlowNodeData = {
  label: string
  nodeType: string
  selected?: boolean
  readOnly?: boolean
  forkOutCount?: number
  hasValidationIssue?: boolean
}

const X_GAP = 220
const Y_GAP = 88
const ORIGIN_X = 48
const ORIGIN_Y = 72

/** 按拓扑分层自动布局（线性 / 并行通用） */
export function computeAutoLayout(plan: WorkflowPlan): Record<string, { x: number; y: number }> {
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const outgoing = new Map<string, string[]>()
  const incoming = new Map<string, string[]>()
  for (const e of edges) {
    outgoing.set(e.from, [...(outgoing.get(e.from) ?? []), e.to])
    incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  }
  const layers = new Map<string, number>()
  if (nodes.some(n => n.id === 'start')) {
    const queue = ['start']
    layers.set('start', 0)
    while (queue.length > 0) {
      const cur = queue.shift()!
      const layer = layers.get(cur) ?? 0
      for (const next of outgoing.get(cur) ?? []) {
        const prev = layers.get(next)
        if (prev == null || prev < layer + 1) {
          layers.set(next, layer + 1)
          queue.push(next)
        }
      }
    }
  }
  for (const n of nodes) {
    if (!layers.has(n.id)) {
      layers.set(n.id, 0)
    }
  }
  const byLayer = new Map<number, string[]>()
  for (const n of nodes) {
    const l = layers.get(n.id) ?? 0
    byLayer.set(l, [...(byLayer.get(l) ?? []), n.id])
  }
  const positions: Record<string, { x: number; y: number }> = {}
  for (const [layer, ids] of [...byLayer.entries()].sort((a, b) => a[0] - b[0])) {
    const sorted = [...ids].sort()
    const totalH = (sorted.length - 1) * Y_GAP
    sorted.forEach((id, i) => {
      positions[id] = {
        x: ORIGIN_X + layer * X_GAP,
        y: ORIGIN_Y + i * Y_GAP - totalH / 2,
      }
    })
  }
  return positions
}

export function resolveNodePositions(plan: WorkflowPlan): Record<string, { x: number; y: number }> {
  const auto = computeAutoLayout(plan)
  const saved = plan.layout ?? {}
  const positions = { ...auto }
  for (const [id, pos] of Object.entries(saved)) {
    if (pos && Number.isFinite(pos.x) && Number.isFinite(pos.y)) {
      positions[id] = { x: pos.x, y: pos.y }
    }
  }
  return positions
}

export function planToFlowElements(
  plan: WorkflowPlan,
  selectedId?: string | null,
  readOnly?: boolean,
  issueNodeIds?: Set<string>,
): { nodes: Node<WorkflowFlowNodeData>[]; edges: Edge[] } {
  const positions = resolveNodePositions(plan)
  const planEdges = (plan.edges ?? []).length > 0
    ? (plan.edges ?? [])
    : rebuildLinearEdges(plan.nodes ?? [])
  const nodes: Node<WorkflowFlowNodeData>[] = (plan.nodes ?? []).map(n => {
    const pos = positions[n.id] ?? { x: ORIGIN_X, y: ORIGIN_Y }
    const forkOutCount = planEdges.filter(e => e.from === n.id).length
    return {
      id: n.id,
      type: WF_FLOW_NODE_TYPE,
      position: { x: pos.x, y: pos.y },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: {
        label: n.displayName?.trim() || n.id,
        nodeType: n.type,
        selected: selectedId === n.id,
        readOnly: !!readOnly,
        forkOutCount,
        hasValidationIssue: issueNodeIds?.has(n.id) ?? false,
      },
      draggable: !readOnly && n.type !== 'start',
      connectable: !readOnly,
      selectable: true,
      deletable: !readOnly && !isProtectedWorkflowNode(n),
    }
  })
  const edges: Edge[] = planEdges.map(e => ({
    id: `${e.from}->${e.to}`,
    source: e.from,
    target: e.to,
    type: 'smoothstep',
  }))
  return { nodes, edges }
}

export function mergeFlowIntoPlan(plan: WorkflowPlan, flowNodes: Node[], flowEdges: Edge[]): WorkflowPlan {
  const flowIds = new Set(flowNodes.map(n => n.id))
  const nodes = (plan.nodes ?? []).filter(n => flowIds.has(n.id) || isProtectedWorkflowNode(n))
  const layout: Record<string, { x: number; y: number }> = {}
  for (const n of flowNodes) {
    layout[n.id] = { x: n.position.x, y: n.position.y }
  }
  const edges = flowEdges.map(e => ({ from: e.source, to: e.target }))
  return reconcilePlanDataFlow({ ...plan, nodes, edges, layout })
}


export function addPlanGraphNode(
  plan: WorkflowPlan,
  type: WorkflowBusinessNodeType | 'join',
  position: { x: number; y: number },
  nodeDefaults?: WorkflowNodeDefaultsResponse | null,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  const id = nextBusinessNodeId(plan, type === 'join' ? 'join' : type)
  const params: Record<string, unknown> = {
    ...(type === 'join' ? buildRetryParams('join', resolved) : {
      ...defaultParamsForType(type as WorkflowBusinessNodeType),
      ...buildRetryParams(type as WorkflowBusinessNodeType, resolved),
    }),
  }
  if (type === 'rag' || type === 'agent') {
    params.query = '{{start.userQuery}}'
    params.context = '{{plan.upstream}}'
  }
  const node: WorkflowPlanNode = {
    id,
    type,
    displayName: type === 'join' ? '汇总' : defaultDisplayName(type as WorkflowBusinessNodeType),
    params,
  }
  const nodes = [...(plan.nodes ?? []), node]
  const layout = { ...(plan.layout ?? {}), [id]: position }
  return reconcilePlanDataFlow({ ...plan, nodes, layout })
}

export function removePlanGraphNode(plan: WorkflowPlan, nodeId: string): WorkflowPlan | null {
  if (nodeId === 'start' || nodeId === 'answer') return null
  const nodes = (plan.nodes ?? []).filter(n => n.id !== nodeId)
  const edges = (plan.edges ?? []).filter(e => e.from !== nodeId && e.to !== nodeId)
  const layout = { ...(plan.layout ?? {}) }
  delete layout[nodeId]
  return reconcilePlanDataFlow({ ...plan, nodes, edges, layout }, { refreshAnswer: true })
}

export function autoLayoutPlan(plan: WorkflowPlan): WorkflowPlan {
  const layout = computeAutoLayout(plan)
  return { ...plan, layout }
}

export type ParallelBranchResult =
  | { ok: true; plan: WorkflowPlan; nodeId: string }
  | { ok: false; reason: string }

/** 在已有 join 的并行 DAG 上追加一条 RAG 分支 */
export function addParallelBranchPlan(
  plan: WorkflowPlan,
  nodeDefaults?: WorkflowNodeDefaultsResponse | null,
): ParallelBranchResult {
  const joinNode = (plan.nodes ?? []).find(n => n.type === 'join')
  if (!joinNode) {
    return { ok: false, reason: '须先添加 Join 节点或应用并行模板' }
  }
  const forkId = findJoinForkPoint(plan, joinNode.id)
  if (!forkId) {
    return { ok: false, reason: '无法识别并行分叉点，请检查 Join 上游连线' }
  }
  const positions = resolveNodePositions(plan)
  const branchYs = (plan.edges ?? [])
    .filter(e => e.from === forkId)
    .map(e => positions[e.to]?.y ?? 72)
  const y = (branchYs.length ? Math.max(...branchYs) : 72) + 88
  const x = (positions[forkId]?.x ?? 48) + 220
  const withNode = addPlanGraphNode(plan, 'rag', { x, y }, nodeDefaults)
  const added = (withNode.nodes ?? []).find(
    n => n.type === 'rag' && !(plan.nodes ?? []).some(p => p.id === n.id),
  )
  if (!added) {
    return { ok: false, reason: '添加并行分支节点失败' }
  }
  const edges = [
    ...(withNode.edges ?? []),
    { from: forkId, to: added.id },
    { from: added.id, to: joinNode.id },
  ]
  return {
    ok: true,
    plan: reconcilePlanDataFlow({ ...withNode, edges }),
    nodeId: added.id,
  }
}

export function stripPlanLayout(plan: WorkflowPlan): WorkflowPlan {
  const { layout: _layout, ...rest } = plan as WorkflowPlan & { layout?: unknown }
  return rest
}
