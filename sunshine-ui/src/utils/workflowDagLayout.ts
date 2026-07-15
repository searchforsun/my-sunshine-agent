import type { Edge, Node } from '@vue-flow/core'
import { Position } from '@vue-flow/core'
import { formatPlanNodeType } from '../api/executionPlans'
import type { WorkflowPlan, WorkflowPlanNode, WorkflowPlanEdge, WorkflowNodeDefaultsResponse } from '../api/workflows'
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
  isLoopType,
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
const LOOP_MIN_WIDTH = 280
const LOOP_MIN_HEIGHT = 160
const LOOP_PAD_X = 24
const LOOP_PAD_TOP = 52
const LOOP_PAD_BOTTOM = 24
const LOOP_INNER_GAP = 40
/** 主干连线共用的 handle 中心 Y */
const SPINE_HANDLE_Y = ORIGIN_Y + BUSINESS_NODE_HEIGHT / 2

export type WorkflowLayoutPos = { x: number; y: number; width?: number; height?: number }

function nodeSize(
  nodeType: string | undefined,
  layoutPos?: Pick<WorkflowLayoutPos, 'width' | 'height'> | null,
): { w: number; h: number } {
  if (nodeType && isGatewayType(nodeType)) {
    return { w: GATEWAY_NODE_WIDTH, h: GATEWAY_NODE_HEIGHT }
  }
  if (nodeType === 'loop') {
    return {
      w: layoutPos?.width && layoutPos.width > 0 ? layoutPos.width : LOOP_MIN_WIDTH,
      h: layoutPos?.height && layoutPos.height > 0 ? layoutPos.height : LOOP_MIN_HEIGHT,
    }
  }
  if (nodeType === 'start' || nodeType === 'answer') {
    return { w: ANCHOR_NODE_WIDTH, h: ANCHOR_NODE_HEIGHT }
  }
  return { w: BUSINESS_NODE_WIDTH, h: BUSINESS_NODE_HEIGHT }
}

function nodeCenterX(pos: WorkflowLayoutPos, nodeType: string | undefined): number {
  return pos.x + nodeSize(nodeType, pos).w / 2
}

function nodeCenterY(pos: WorkflowLayoutPos, nodeType: string | undefined): number {
  return pos.y + nodeSize(nodeType, pos).h / 2
}

function positionFromCenter(
  centerX: number,
  centerY: number,
  nodeType: string | undefined,
  layoutPos?: Pick<WorkflowLayoutPos, 'width' | 'height'> | null,
): WorkflowLayoutPos {
  const { w, h } = nodeSize(nodeType, layoutPos)
  return { x: centerX - w / 2, y: centerY - h / 2, width: layoutPos?.width, height: layoutPos?.height }
}

function handleCenterY(
  nodeId: string,
  positions: Record<string, WorkflowLayoutPos>,
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
  positions: Record<string, WorkflowLayoutPos>,
  typeById: Map<string, string>,
): 'straight' | 'smoothstep' {
  const dy = Math.abs(handleCenterY(from, positions, typeById) - handleCenterY(to, positions, typeById))
  return dy < 1 ? 'straight' : 'smoothstep'
}

/** 框内 body 线性顺序（与引擎 loopBodyOrder 一致） */
export function orderLoopBody(plan: WorkflowPlan, loopId: string): string[] {
  const bodyIds = (plan.nodes ?? []).filter(n => n.parentId === loopId).map(n => n.id)
  if (bodyIds.length === 0) return []
  const bodySet = new Set(bodyIds)
  const outs = new Map<string, string[]>()
  const indeg = new Map<string, number>()
  for (const id of bodyIds) {
    outs.set(id, [])
    indeg.set(id, 0)
  }
  for (const e of plan.edges ?? []) {
    if (!bodySet.has(e.from) || !bodySet.has(e.to)) continue
    outs.get(e.from)!.push(e.to)
    indeg.set(e.to, (indeg.get(e.to) ?? 0) + 1)
  }
  const roots = bodyIds.filter(id => (indeg.get(id) ?? 0) === 0)
  let cur = roots.length === 1 ? roots[0] : bodyIds[0]
  const order: string[] = []
  const seen = new Set<string>()
  while (cur && !seen.has(cur)) {
    seen.add(cur)
    order.push(cur)
    const nexts = outs.get(cur) ?? []
    cur = nexts[0]
  }
  for (const id of bodyIds) {
    if (!seen.has(id)) order.push(id)
  }
  return order
}

function measureLoopSize(bodyCount: number): { width: number; height: number } {
  if (bodyCount <= 0) {
    return { width: LOOP_MIN_WIDTH, height: LOOP_MIN_HEIGHT }
  }
  const innerStep = BUSINESS_NODE_WIDTH + LOOP_INNER_GAP
  const width = Math.max(
    LOOP_MIN_WIDTH,
    LOOP_PAD_X + (bodyCount - 1) * innerStep + BUSINESS_NODE_WIDTH + LOOP_PAD_X,
  )
  const height = Math.max(LOOP_MIN_HEIGHT, LOOP_PAD_TOP + BUSINESS_NODE_HEIGHT + LOOP_PAD_BOTTOM)
  return { width, height }
}

function layoutLoopBody(
  plan: WorkflowPlan,
  loopId: string,
  loopPos: WorkflowLayoutPos,
): Record<string, WorkflowLayoutPos> {
  const order = orderLoopBody(plan, loopId)
  const innerStep = BUSINESS_NODE_WIDTH + LOOP_INNER_GAP
  const out: Record<string, WorkflowLayoutPos> = {}
  order.forEach((id, i) => {
    out[id] = {
      x: loopPos.x + LOOP_PAD_X + i * innerStep,
      y: loopPos.y + LOOP_PAD_TOP,
    }
  })
  return out
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
      ? (nodeCenterX(positions[pg.id], pg.type) + nodeCenterX(positions[join.id], join.type)) / 2
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
      nodeCenterY(positions[loop.id] ?? { x: ORIGIN_X, y: ORIGIN_Y }, 'loop'),
      'loop',
      size,
    )
    positions[loop.id] = { ...centered, width: size.width, height: size.height }
    Object.assign(positions, layoutLoopBody(plan, loop.id, positions[loop.id]))
  }
  return positions
}

export function resolveNodePositions(plan: WorkflowPlan): Record<string, WorkflowLayoutPos> {
  return { ...(plan.layout ?? {}) }
}

function edgePairsFingerprint(pairs: { from: string; to: string; default?: boolean; condition?: { left?: string; op?: string; right?: string } }[]): string {
  return JSON.stringify(pairs.map(p =>
    `${p.from}->${p.to}|${p.default ? 'd' : ''}|${p.condition?.op ?? ''}:${p.condition?.left ?? ''}:${p.condition?.right ?? ''}`,
  ).sort())
}

export function planEdgeFingerprint(plan: WorkflowPlan): string {
  return edgePairsFingerprint(plan.edges ?? [])
}

export function flowEdgeFingerprint(flowEdges: Edge[]): string {
  return edgePairsFingerprint(flowEdges.map(e => ({ from: e.source, to: e.target })))
}

function buildFlowEdges(
  planEdges: WorkflowPlanEdge[],
  plan?: WorkflowPlan,
  positions?: Record<string, WorkflowLayoutPos>,
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

function flowNodeDimensions(n: WorkflowPlanNode, pos: WorkflowLayoutPos): { width?: number; height?: number; style?: Record<string, string> } {
  if (!isLoopType(n.type)) return {}
  const { w, h } = nodeSize(n.type, pos)
  return {
    width: w,
    height: h,
    style: { width: `${w}px`, height: `${h}px` },
  }
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
    const abs = positions[n.id] ?? { x: ORIGIN_X, y: ORIGIN_Y }
    let position = { x: abs.x, y: abs.y }
    if (n.parentId) {
      const parentAbs = positions[n.parentId] ?? { x: ORIGIN_X, y: ORIGIN_Y }
      position = {
        x: Math.max(LOOP_PAD_X, abs.x - parentAbs.x),
        y: Math.max(LOOP_PAD_TOP, abs.y - parentAbs.y),
      }
    }
    const dims = flowNodeDimensions(n, abs)
    const flowNode: Node<WorkflowFlowNodeData> = {
      id: n.id,
      type: WF_FLOW_NODE_TYPE,
      position,
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
      ...dims,
    }
    if (n.parentId) {
      flowNode.parentNode = n.parentId
      flowNode.extent = 'parent'
      flowNode.expandParent = true
    }
    return flowNode
  })
  // 父节点须先于子节点，便于 Vue Flow 挂载
  nodes.sort((a, b) => {
    const ap = a.parentNode ? 1 : 0
    const bp = b.parentNode ? 1 : 0
    return ap - bp
  })
  const edges = buildFlowEdges(planEdges, plan, positions)
  return { nodes, edges }
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

/** 选中 loop 或其框内节点时，新业务节点应归入该容器 */
export function resolveLoopParentForAdd(
  plan: WorkflowPlan,
  selectedNodeId?: string | null,
): string | null {
  if (!selectedNodeId) return null
  const node = (plan.nodes ?? []).find(n => n.id === selectedNodeId)
  if (!node) return null
  if (isLoopType(node.type)) return node.id
  if (node.parentId) {
    const parent = (plan.nodes ?? []).find(n => n.id === node.parentId)
    if (parent && isLoopType(parent.type)) return parent.id
  }
  return null
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

function addLoopBodyNode(
  plan: WorkflowPlan,
  type: WorkflowBusinessNodeType,
  loopId: string,
  selectedNodeId: string | null,
  resolved: ReturnType<typeof resolveNodeDefaults>,
): WorkflowPlan {
  const id = nextBusinessNodeId(plan, type)
  const params: Record<string, unknown> = {
    ...defaultParamsForType(type),
    ...buildRetryParams(type, resolved),
  }
  if (type === 'rag' || type === 'agent') {
    params.query = '{{start.userQuery}}'
    params.context = '{{plan.upstream}}'
  }
  const node: WorkflowPlanNode = {
    id,
    type,
    displayName: defaultDisplayName(type),
    parentId: loopId,
    params,
  }
  const nodes = [...(plan.nodes ?? []), node]
  const afterId = resolveLoopBodyInsertAfter(plan, loopId, selectedNodeId)
  const edges = [...(plan.edges ?? [])]
  if (afterId) {
    edges.push({ from: afterId, to: id })
  }
  const draft = reconcilePlanDataFlow({ ...plan, nodes, edges, layout: { ...(plan.layout ?? {}) } })
  // 框内追加后按当前 loop 原点重排 body + 撑开尺寸（不重排外图）
  const positions = resolveNodePositions(draft)
  const loopPos = positions[loopId] ?? { x: ORIGIN_X, y: ORIGIN_Y }
  const bodyLayout = layoutLoopBody(draft, loopId, loopPos)
  const size = measureLoopSize(orderLoopBody(draft, loopId).length)
  const layout: Record<string, WorkflowLayoutPos> = {
    ...positions,
    ...bodyLayout,
    [loopId]: { ...loopPos, width: size.width, height: size.height },
  }
  return reconcilePlanDataFlow({ ...draft, layout })
}

/** 在框内线性链尾（或选中的框内节点后）插入 */
function resolveLoopBodyInsertAfter(
  plan: WorkflowPlan,
  loopId: string,
  selectedNodeId: string | null,
): string | null {
  const bodyIds = new Set(
    (plan.nodes ?? []).filter(n => n.parentId === loopId).map(n => n.id),
  )
  if (bodyIds.size === 0) return null
  if (selectedNodeId && bodyIds.has(selectedNodeId)) return selectedNodeId
  const outs = new Map<string, string[]>()
  for (const id of bodyIds) outs.set(id, [])
  for (const e of plan.edges ?? []) {
    if (bodyIds.has(e.from) && bodyIds.has(e.to)) {
      outs.get(e.from)!.push(e.to)
    }
  }
  const tails = [...bodyIds].filter(id => (outs.get(id) ?? []).length === 0)
  return tails[0] ?? [...bodyIds][0] ?? null
}

function addLoopContainer(
  plan: WorkflowPlan,
  position: { x: number; y: number },
  resolved: ReturnType<typeof resolveNodeDefaults>,
): WorkflowPlan {
  const used = new Set((plan.nodes ?? []).map(n => n.id))
  let id = `loop-${Math.random().toString(16).slice(2, 10)}`
  while (used.has(id)) {
    id = `loop-${Math.random().toString(16).slice(2, 10)}`
  }
  const bodyId = nextBusinessNodeId(plan, 'rag')
  const loopNode: WorkflowPlanNode = {
    id,
    type: 'loop',
    displayName: '循环',
    params: {
      ...buildRetryParams('loop', resolved),
      'condition.left': '{{start.userQuery}}',
      'condition.op': 'contains',
      'condition.right': '',
      maxIterations: '3',
      onMaxIterations: 'fail_fast',
    },
  }
  const bodyNode: WorkflowPlanNode = {
    id: bodyId,
    type: 'rag',
    displayName: defaultDisplayName('rag'),
    parentId: id,
    params: {
      ...defaultParamsForType('rag'),
      ...buildRetryParams('rag', resolved),
      query: '{{start.userQuery}}',
      context: '{{plan.upstream}}',
    },
  }
  const nodes = [...(plan.nodes ?? []), loopNode, bodyNode]
  const size = measureLoopSize(1)
  const layout = {
    ...(plan.layout ?? {}),
    [id]: { ...position, width: size.width, height: size.height },
    [bodyId]: { x: position.x + LOOP_PAD_X, y: position.y + LOOP_PAD_TOP },
  }
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
