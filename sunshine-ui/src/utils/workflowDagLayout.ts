/** Studio 画布编辑：增删改节点 / 自动布局 / merge。只读投影见 workflowFlowProjection。 */
import type { Edge, Node } from '@vue-flow/core'
import type { WorkflowPlan, WorkflowPlanNode, WorkflowPlanEdge, WorkflowNodeDefaultsResponse } from '../api/workflows'
import {
  defaultDisplayName,
  defaultParamsForType,
  nextBusinessNodeId,
  reconcilePlanDataFlow,
  type WorkflowBusinessNodeType,
} from './workflowPlan'
import { buildRetryParams, resolveNodeDefaults } from './workflowNodeParams'
import {
  defaultGatewayDisplayName,
  gatewayNodeIdPrefix,
  isGatewayType,
  isLoopType,
  type WorkflowGatewayType,
} from './workflowGateway'
import { findJoinForkPoint } from './workflowPlanValidation'
import {
  BUSINESS_NODE_WIDTH,
  ORIGIN_X,
  ORIGIN_Y,
  SPINE_HANDLE_Y,
  Y_GAP,
  measureLoopSize,
  nodeCenterX,
  nodeCenterY,
  nodeSize as metricsNodeSize,
  positionFromCenter as metricsPositionFromCenter,
  type WorkflowLayoutPos,
} from './workflowDagLayoutMetrics'
import {
  addLoopBodyNode,
  addLoopContainer,
  layoutLoopBody,
  resolveLoopParentForAdd,
} from './workflowLoopLayout'
import {
  isProtectedWorkflowNode,
  resolveNodePositions,
} from './workflowFlowProjection'

export { evaluateConnection, isValidConnection } from './workflowPlanValidation'
export type { WorkflowLayoutPos } from './workflowDagLayoutMetrics'
export { orderLoopBody, resolveLoopParentForAdd } from './workflowLoopLayout'
export {
  WF_FLOW_NODE_TYPE,
  WF_FIT_VIEW_MAX_ZOOM,
  WF_FIT_VIEW_OPTS,
  PROTECTED_WORKFLOW_NODE_IDS,
  isProtectedWorkflowNode,
  planToFlowElements,
  resolveNodePositions,
  planEdgeFingerprint,
  flowEdgeFingerprint,
  flowEdgesFromPlan,
  type WorkflowFlowExecOverlay,
  type WorkflowFlowNodeData,
} from './workflowFlowProjection'

function nodeSize(nodeType: string | undefined, layoutPos?: Pick<WorkflowLayoutPos, 'width' | 'height'> | null) {
  return metricsNodeSize(nodeType, layoutPos, isGatewayType)
}

function positionFromCenter(
  centerX: number,
  centerY: number,
  nodeType: string | undefined,
  layoutPos?: Pick<WorkflowLayoutPos, 'width' | 'height'> | null,
): WorkflowLayoutPos {
  return metricsPositionFromCenter(centerX, centerY, nodeType, layoutPos, isGatewayType)
}

function handleCenterY(
  nodeId: string,
  positions: Record<string, WorkflowLayoutPos>,
  typeById: Map<string, string>,
): number {
  const pos = positions[nodeId]
  if (!pos) return SPINE_HANDLE_Y
  return nodeCenterY(pos, typeById.get(nodeId), isGatewayType)
}

function distributeCentersAroundSpine(count: number): number[] {
  if (count <= 0) return []
  if (count === 1) return [SPINE_HANDLE_Y]
  return Array.from({ length: count }, (_, i) => SPINE_HANDLE_Y - ((count - 1) * Y_GAP) / 2 + i * Y_GAP)
}

/** 按拓扑分层自动布局：外图仅含无 parentId 节点；loop 框内单独线性布局并撑开尺寸 */
export function computeAutoLayout(plan: WorkflowPlan): Record<string, WorkflowLayoutPos> {
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const byId = new Map(nodes.map(n => [n.id, n]))
  const typeById = new Map(nodes.map(n => [n.id, n.type]))
  const outerNodes = nodes.filter(n => !n.parentId)
  const outerEdges = edges.filter(e => {
    const from = byId.get(e.from)
    const to = byId.get(e.to)
    return !!from && !!to && !from.parentId && !to.parentId
  })
  const outgoing = new Map<string, string[]>()
  for (const e of outerEdges) {
    outgoing.set(e.from, [...(outgoing.get(e.from) ?? []), e.to])
  }
  const layers = new Map<string, number>()
  if (outerNodes.some(n => n.id === 'start')) {
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
  for (const n of outerNodes) {
    if (!layers.has(n.id)) layers.set(n.id, 0)
  }
  const byLayer = new Map<number, string[]>()
  for (const n of outerNodes) {
    const l = layers.get(n.id) ?? 0
    byLayer.set(l, [...(byLayer.get(l) ?? []), n.id])
  }
  const layerWidth = (layer: number): number => {
    const ids = byLayer.get(layer) ?? []
    if (ids.length === 0) return BUSINESS_NODE_WIDTH
    return Math.max(...ids.map(id => {
      const n = byId.get(id)!
      if (isLoopType(n.type)) {
        const bodyCount = (plan.nodes ?? []).filter(b => b.parentId === n.id).length
        return measureLoopSize(bodyCount).width
      }
      return nodeSize(n.type).w
    }))
  }
  const LAYER_GAP = 80
  const layerLeft = new Map<number, number>()
  let cursorX = ORIGIN_X
  for (const layer of [...byLayer.keys()].sort((a, b) => a - b)) {
    layerLeft.set(layer, cursorX)
    cursorX += layerWidth(layer) + LAYER_GAP
  }
  const layerCenterX = (layer: number): number =>
    (layerLeft.get(layer) ?? ORIGIN_X) + layerWidth(layer) / 2

  const positions: Record<string, WorkflowLayoutPos> = {}
  for (const n of outerNodes) {
    const layer = layers.get(n.id) ?? 0
    const bodyCount = isLoopType(n.type)
      ? (plan.nodes ?? []).filter(b => b.parentId === n.id).length
      : 0
    const size = isLoopType(n.type) ? measureLoopSize(bodyCount) : null
    positions[n.id] = {
      ...positionFromCenter(layerCenterX(layer), SPINE_HANDLE_Y, n.type, size),
      ...(size ? { width: size.width, height: size.height } : {}),
    }
  }
  const parallelBranchIds = new Set<string>()
  for (const pg of outerNodes.filter(n => n.type === 'parallel-gateway')) {
    const branches = [...(outgoing.get(pg.id) ?? [])].sort()
    if (branches.length < 2) continue
    const join = outerNodes.find(j => j.type === 'join' && findJoinForkPoint(plan, j.id) === pg.id)
    const branchCenterX = join
      ? (nodeCenterX(positions[pg.id], pg.type, isGatewayType) + nodeCenterX(positions[join.id], join.type, isGatewayType)) / 2
      : layerCenterX(layers.get(branches[0]) ?? 0)
    const centers = distributeCentersAroundSpine(branches.length)
    branches.forEach((id, i) => {
      parallelBranchIds.add(id)
      positions[id] = positionFromCenter(branchCenterX, centers[i], typeById.get(id))
    })
  }
  for (const ids of byLayer.values()) {
    if (ids.length <= 1) continue
    const unassigned = ids.filter(id => !parallelBranchIds.has(id))
    if (unassigned.length <= 1) continue
    const sorted = [...unassigned].sort()
    const centers = distributeCentersAroundSpine(sorted.length)
    sorted.forEach((id, i) => {
      const layer = layers.get(id) ?? 0
      const prev = positions[id]
      positions[id] = {
        ...positionFromCenter(layerCenterX(layer), centers[i], typeById.get(id), prev),
        width: prev?.width,
        height: prev?.height,
      }
    })
  }
  // 框内线性布局 + 用真实尺寸校正 loop 中心
  for (const loop of outerNodes.filter(n => isLoopType(n.type))) {
    const layer = layers.get(loop.id) ?? 0
    const bodyCount = (plan.nodes ?? []).filter(b => b.parentId === loop.id).length
    const size = measureLoopSize(bodyCount)
    const centered = positionFromCenter(
      layerCenterX(layer),
      nodeCenterY(positions[loop.id] ?? { x: ORIGIN_X, y: ORIGIN_Y }, 'loop', isGatewayType),
      'loop',
      size,
    )
    positions[loop.id] = { ...centered, width: size.width, height: size.height }
    Object.assign(positions, layoutLoopBody(plan, loop.id, positions[loop.id]))
  }
  return positions
}

function readFlowNodeSize(n: Node): { width?: number; height?: number } {
  const anyNode = n as Node & { dimensions?: { width?: number; height?: number } }
  const w = typeof n.width === 'number' && n.width > 0
    ? n.width
    : (typeof anyNode.dimensions?.width === 'number' && anyNode.dimensions.width > 0
      ? anyNode.dimensions.width
      : undefined)
  const h = typeof n.height === 'number' && n.height > 0
    ? n.height
    : (typeof anyNode.dimensions?.height === 'number' && anyNode.dimensions.height > 0
      ? anyNode.dimensions.height
      : undefined)
  if (w != null || h != null) return { width: w, height: h }
  const style = n.style as Record<string, string> | undefined
  if (!style) return {}
  const sw = Number.parseFloat(String(style.width ?? ''))
  const sh = Number.parseFloat(String(style.height ?? ''))
  return {
    width: Number.isFinite(sw) && sw > 0 ? sw : undefined,
    height: Number.isFinite(sh) && sh > 0 ? sh : undefined,
  }
}

export function mergeFlowIntoPlan(plan: WorkflowPlan, flowNodes: Node[], flowEdges: Edge[]): WorkflowPlan {
  const flowIds = new Set(flowNodes.map(n => n.id))
  const prevById = new Map((plan.nodes ?? []).map(n => [n.id, n]))
  const flowById = new Map(flowNodes.map(n => [n.id, n]))
  const nodes = (plan.nodes ?? []).filter(n => flowIds.has(n.id) || isProtectedWorkflowNode(n))
  const prevLayout = plan.layout ?? {}
  const layout: Record<string, WorkflowLayoutPos> = {}
  for (const n of flowNodes) {
    if (n.parentNode) {
      const parent = flowById.get(n.parentNode)
      const px = parent?.position.x ?? 0
      const py = parent?.position.y ?? 0
      layout[n.id] = { x: px + n.position.x, y: py + n.position.y }
    } else {
      const size = readFlowNodeSize(n)
      const prev = prevLayout[n.id]
      layout[n.id] = {
        x: n.position.x,
        y: n.position.y,
        width: size.width ?? prev?.width,
        height: size.height ?? prev?.height,
      }
    }
  }
  const mergedNodes = nodes.map(n => {
    const prev = prevById.get(n.id)
    return prev ? { ...prev } : n
  })
  const prevByKey = new Map((plan.edges ?? []).map(e => [`${e.from}->${e.to}`, e]))
  const edges: WorkflowPlanEdge[] = flowEdges.map(e => {
    const prev = prevByKey.get(`${e.source}->${e.target}`)
    const next: WorkflowPlanEdge = { from: e.source, to: e.target }
    if (prev?.default) next.default = true
    if (prev?.condition) next.condition = { ...prev.condition }
    return next
  })
  return reconcilePlanDataFlow({ ...plan, nodes: mergedNodes, edges, layout })
}

export function addPlanGraphNode(
  plan: WorkflowPlan,
  type: WorkflowBusinessNodeType | WorkflowGatewayType | 'loop',
  position: { x: number; y: number },
  nodeDefaults?: WorkflowNodeDefaultsResponse | null,
  options?: { selectedNodeId?: string | null },
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  if (type === 'loop') {
    return addLoopContainer(plan, position, resolved)
  }
  const parentLoopId = resolveLoopParentForAdd(plan, options?.selectedNodeId)
  if (parentLoopId && isGatewayType(type)) {
    // 框内禁止网关；落到外图（忽略选中 loop）
    return addOuterPlanGraphNode(plan, type, position, resolved)
  }
  if (parentLoopId && (type === 'rag' || type === 'tool' || type === 'agent')) {
    return addLoopBodyNode(plan, type, parentLoopId, options?.selectedNodeId ?? null, resolved)
  }
  return addOuterPlanGraphNode(plan, type, position, resolved)
}

function addOuterPlanGraphNode(
  plan: WorkflowPlan,
  type: WorkflowBusinessNodeType | WorkflowGatewayType,
  position: { x: number; y: number },
  resolved: ReturnType<typeof resolveNodeDefaults>,
): WorkflowPlan {
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
  const removing = new Set([nodeId])
  for (const n of plan.nodes ?? []) {
    if (n.parentId === nodeId) removing.add(n.id)
  }
  const nodes = (plan.nodes ?? []).filter(n => !removing.has(n.id))
  const edges = (plan.edges ?? []).filter(e => !removing.has(e.from) && !removing.has(e.to))
  const layout = { ...(plan.layout ?? {}) }
  for (const id of removing) delete layout[id]
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
  if (!nodeDefaults) {
    return { ok: false, reason: '节点默认策略未加载，请刷新页面后重试' }
  }
  const positions = resolveNodePositions(plan)
  const typeById = new Map((plan.nodes ?? []).map(n => [n.id, n.type]))
  const branchIds = (plan.edges ?? []).filter(e => e.from === forkId).map(e => e.to)
  const branchCenters = branchIds.map(id => handleCenterY(id, positions, typeById))
  const newCenter = (branchCenters.length ? Math.max(...branchCenters) : SPINE_HANDLE_Y) + Y_GAP
  const branchCenterX = (nodeCenterX(positions[forkId], typeById.get(forkId), isGatewayType)
    + nodeCenterX(positions[joinNode.id], joinNode.type, isGatewayType)) / 2
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
