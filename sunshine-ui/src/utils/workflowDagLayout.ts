import type { Edge, Node } from '@vue-flow/core'
import { Position } from '@vue-flow/core'
import { formatPlanNodeType } from '../api/executionPlans'
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
import {
  defaultGatewayDisplayName,
  gatewayNodeIdPrefix,
  isGatewayType,
  type WorkflowGatewayType,
} from './workflowGateway'
import { findJoinForkPoint } from './workflowPlanValidation'

export { evaluateConnection, isValidConnection } from './workflowPlanValidation'

export const WF_FLOW_NODE_TYPE = 'workflowNode'

/**
 * fitView / [] / 切换工作流：节点至多 1× 设计尺寸，少量节点不再被放大。
 * +/- 仍受 VueFlow maxZoom（如 1.75）约束，可手动放大。
 */
export const WF_FIT_VIEW_MAX_ZOOM = 1
export const WF_FIT_VIEW_OPTS = {
  padding: 0.22,
  duration: 0,
  maxZoom: WF_FIT_VIEW_MAX_ZOOM,
} as const

/** 流程锚点节点，画布不可删除 */
export const PROTECTED_WORKFLOW_NODE_IDS = new Set(['start', 'answer'])

export function isProtectedWorkflowNode(node: Pick<WorkflowPlanNode, 'id' | 'type'>): boolean {
  return node.type === 'start' || node.type === 'answer' || PROTECTED_WORKFLOW_NODE_IDS.has(node.id)
}

function flowNodeLabel(node: WorkflowPlanNode): string {
  if (node.type === 'answer') return formatPlanNodeType('answer')
  return node.displayName?.trim() || node.id
}

export type WorkflowFlowExecOverlay = {
  status?: 'pending' | 'running' | 'done' | 'error' | 'awaiting_confirm' | 'paused' | 'terminated' | 'skipped'
  durationMs?: number
  retryBadge?: number | null
  live?: boolean
  recoveryAwaiting?: boolean
}

export type WorkflowFlowNodeData = {
  label: string
  nodeType: string
  selected?: boolean
  readOnly?: boolean
  hasValidationIssue?: boolean
  /** Chat 执行态角标（Studio 画布无此字段） */
  exec?: WorkflowFlowExecOverlay
}

const X_GAP = 220
const Y_GAP = 88
const ORIGIN_X = 48
const ORIGIN_Y = 72
/** 与 WorkflowFlowNode 尺寸对齐，布局按中心点计算 */
const BUSINESS_NODE_WIDTH = 148
const BUSINESS_NODE_HEIGHT = 56
const ANCHOR_NODE_WIDTH = 56
const ANCHOR_NODE_HEIGHT = 56
const GATEWAY_NODE_WIDTH = 40
const GATEWAY_NODE_HEIGHT = 40
/** 主干连线共用的 handle 中心 Y */
const SPINE_HANDLE_Y = ORIGIN_Y + BUSINESS_NODE_HEIGHT / 2

function nodeSize(nodeType: string | undefined): { w: number; h: number } {
  if (nodeType && isGatewayType(nodeType)) {
    return { w: GATEWAY_NODE_WIDTH, h: GATEWAY_NODE_HEIGHT }
  }
  if (nodeType === 'start' || nodeType === 'answer') {
    return { w: ANCHOR_NODE_WIDTH, h: ANCHOR_NODE_HEIGHT }
  }
  return { w: BUSINESS_NODE_WIDTH, h: BUSINESS_NODE_HEIGHT }
}

function nodeCenterX(pos: { x: number; y: number }, nodeType: string | undefined): number {
  return pos.x + nodeSize(nodeType).w / 2
}

function nodeCenterY(pos: { x: number; y: number }, nodeType: string | undefined): number {
  return pos.y + nodeSize(nodeType).h / 2
}

function positionFromCenter(centerX: number, centerY: number, nodeType: string | undefined): { x: number; y: number } {
  const { w, h } = nodeSize(nodeType)
  return { x: centerX - w / 2, y: centerY - h / 2 }
}

/** 每列业务节点中心 X；网关同列共用该中心，避免宽节点与菱形 left 对齐导致并行区偏移 */
function columnCenterX(layer: number): number {
  return ORIGIN_X + layer * X_GAP + BUSINESS_NODE_WIDTH / 2
}

function handleCenterY(
  nodeId: string,
  positions: Record<string, { x: number; y: number }>,
  typeById: Map<string, string>,
): number {
  const pos = positions[nodeId]
  if (!pos) return SPINE_HANDLE_Y
  return nodeCenterY(pos, typeById.get(nodeId))
}

function distributeCentersAroundSpine(count: number): number[] {
  if (count <= 0) return []
  if (count === 1) return [SPINE_HANDLE_Y]
  return Array.from({ length: count }, (_, i) => SPINE_HANDLE_Y - ((count - 1) * Y_GAP) / 2 + i * Y_GAP)
}

function edgePathType(
  from: string,
  to: string,
  positions: Record<string, { x: number; y: number }>,
  typeById: Map<string, string>,
): 'straight' | 'smoothstep' {
  const dy = Math.abs(handleCenterY(from, positions, typeById) - handleCenterY(to, positions, typeById))
  return dy < 1 ? 'straight' : 'smoothstep'
}

/** 按拓扑分层自动布局（线性 / 并行通用；Y 轴以 handle 中心线对齐） */
export function computeAutoLayout(plan: WorkflowPlan): Record<string, { x: number; y: number }> {
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const typeById = new Map(nodes.map(n => [n.id, n.type]))
  const outgoing = new Map<string, string[]>()
  for (const e of edges) {
    outgoing.set(e.from, [...(outgoing.get(e.from) ?? []), e.to])
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
  // 默认：按列中心 + 主干 handle 线放置（网关/业务宽度不同也共线）
  for (const n of nodes) {
    const layer = layers.get(n.id) ?? 0
    positions[n.id] = positionFromCenter(columnCenterX(layer), SPINE_HANDLE_Y, n.type)
  }
  const parallelBranchIds = new Set<string>()
  for (const pg of nodes.filter(n => n.type === 'parallel-gateway')) {
    const branches = [...(outgoing.get(pg.id) ?? [])].sort()
    if (branches.length < 2) continue
    const join = nodes.find(j => j.type === 'join' && findJoinForkPoint(plan, j.id) === pg.id)
    const branchCenterX = join
      ? (nodeCenterX(positions[pg.id], pg.type) + nodeCenterX(positions[join.id], join.type)) / 2
      : columnCenterX(layers.get(branches[0]) ?? 0)
    const centers = distributeCentersAroundSpine(branches.length)
    branches.forEach((id, i) => {
      parallelBranchIds.add(id)
      positions[id] = positionFromCenter(branchCenterX, centers[i], typeById.get(id))
    })
  }
  // 非并行分叉、同层多节点：围绕主干对称分布
  for (const ids of byLayer.values()) {
    if (ids.length <= 1) continue
    const unassigned = ids.filter(id => !parallelBranchIds.has(id))
    if (unassigned.length <= 1) continue
    const sorted = [...unassigned].sort()
    const centers = distributeCentersAroundSpine(sorted.length)
    sorted.forEach((id, i) => {
      const layer = layers.get(id) ?? 0
      positions[id] = positionFromCenter(columnCenterX(layer), centers[i], typeById.get(id))
    })
  }
  return positions
}

export function resolveNodePositions(plan: WorkflowPlan): Record<string, { x: number; y: number }> {
  return { ...(plan.layout ?? {}) }
}

function edgePairsFingerprint(pairs: { from: string; to: string }[]): string {
  return JSON.stringify(pairs.map(p => `${p.from}->${p.to}`).sort())
}

export function planEdgeFingerprint(plan: WorkflowPlan): string {
  return edgePairsFingerprint(plan.edges ?? [])
}

export function flowEdgeFingerprint(flowEdges: Edge[]): string {
  return edgePairsFingerprint(flowEdges.map(e => ({ from: e.source, to: e.target })))
}

function buildFlowEdges(
  planEdges: { from: string; to: string }[],
  plan?: WorkflowPlan,
  positions?: Record<string, { x: number; y: number }>,
): Edge[] {
  const typeById = new Map((plan?.nodes ?? []).map(n => [n.id, n.type]))
  const layout = positions ?? (plan ? resolveNodePositions(plan) : null)
  return planEdges.map(e => ({
    id: `${e.from}->${e.to}`,
    source: e.from,
    target: e.to,
    type: layout ? edgePathType(e.from, e.to, layout, typeById) : 'smoothstep',
    selectable: true,
    focusable: true,
    interactionWidth: 20,
  }))
}

/** 从 plan.edges 生成 Vue Flow 边列表（画布 SSOT 对齐用） */
export function flowEdgesFromPlan(plan: WorkflowPlan): Edge[] {
  const planEdges = (plan.edges ?? []).length > 0
    ? (plan.edges ?? [])
    : rebuildLinearEdges(plan.nodes ?? [])
  return buildFlowEdges(planEdges, plan)
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
    return {
      id: n.id,
      type: WF_FLOW_NODE_TYPE,
      position: { x: pos.x, y: pos.y },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      data: {
        label: flowNodeLabel(n),
        nodeType: n.type,
        selected: selectedId === n.id,
        readOnly: !!readOnly,
        hasValidationIssue: issueNodeIds?.has(n.id) ?? false,
      },
      draggable: !readOnly && n.type !== 'start',
      connectable: !readOnly,
      selectable: true,
      deletable: !readOnly && !isProtectedWorkflowNode(n),
    }
  })
  const edges = buildFlowEdges(planEdges, plan, positions)
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
  type: WorkflowBusinessNodeType | WorkflowGatewayType,
  position: { x: number; y: number },
  nodeDefaults?: WorkflowNodeDefaultsResponse | null,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  const gateway = isGatewayType(type)
  const id = gateway
    ? nextGatewayNodeId(plan, type)
    : nextBusinessNodeId(plan, type)
  let params: Record<string, unknown>
  if (gateway) {
    params = buildRetryParams(type, resolved)
  } else {
    params = {
      ...defaultParamsForType(type),
      ...buildRetryParams(type, resolved),
    }
    if (type === 'rag' || type === 'agent') {
      params.query = '{{start.userQuery}}'
      params.context = '{{plan.upstream}}'
    }
  }
  const node: WorkflowPlanNode = {
    id,
    type,
    displayName: gateway ? defaultGatewayDisplayName(type) : defaultDisplayName(type),
    params,
  }
  const nodes = [...(plan.nodes ?? []), node]
  const layout = { ...(plan.layout ?? {}), [id]: position }
  return reconcilePlanDataFlow({ ...plan, nodes, layout })
}

function nextGatewayNodeId(plan: WorkflowPlan, type: WorkflowGatewayType): string {
  const prefix = gatewayNodeIdPrefix(type)
  const used = new Set((plan.nodes ?? []).map(n => n.id))
  for (let i = 0; i < 100; i++) {
    const suffix = Math.random().toString(16).slice(2, 10)
    const id = `${prefix}-${suffix}`
    if (!used.has(id)) return id
  }
  return `${prefix}-${Date.now().toString(16)}`
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
  const typeById = new Map((plan.nodes ?? []).map(n => [n.id, n.type]))
  const branchIds = (plan.edges ?? []).filter(e => e.from === forkId).map(e => e.to)
  const branchCenters = branchIds.map(id => handleCenterY(id, positions, typeById))
  const newCenter = (branchCenters.length ? Math.max(...branchCenters) : SPINE_HANDLE_Y) + Y_GAP
  const branchCenterX = (nodeCenterX(positions[forkId], typeById.get(forkId))
    + nodeCenterX(positions[joinNode.id], joinNode.type)) / 2
  const { x, y } = positionFromCenter(branchCenterX, newCenter, 'rag')
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
