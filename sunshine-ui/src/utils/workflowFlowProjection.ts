/** Studio / Chat 共享的只读 Vue Flow 投影（plan → nodes/edges）。
 * Chat 只应依赖本模块 + WorkflowFlowNode；禁止引入 workflowDagLayout 的编辑 API。
 */
import type { Edge, Node } from '@vue-flow/core'
import { Position } from '@vue-flow/core'
import { formatPlanNodeType } from '../api/executionPlans'
import type { WorkflowPlan, WorkflowPlanNode, WorkflowPlanEdge } from '../api/workflows'
import { rebuildLinearEdges } from './workflowPlan'
import { isGatewayType, isLoopType } from './workflowGateway'
import {
  LOOP_PAD_TOP,
  LOOP_PAD_X,
  ORIGIN_X,
  ORIGIN_Y,
  SPINE_HANDLE_Y,
  nodeSize as metricsNodeSize,
  nodeCenterY,
  type WorkflowLayoutPos,
} from './workflowDagLayoutMetrics'

export type { WorkflowLayoutPos } from './workflowDagLayoutMetrics'

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

function nodeSize(nodeType: string | undefined, layoutPos?: Pick<WorkflowLayoutPos, 'width' | 'height'> | null) {
  return metricsNodeSize(nodeType, layoutPos, isGatewayType)
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

function edgePathType(
  from: string,
  to: string,
  positions: Record<string, WorkflowLayoutPos>,
  typeById: Map<string, string>,
): 'straight' | 'smoothstep' {
  const dy = Math.abs(handleCenterY(from, positions, typeById) - handleCenterY(to, positions, typeById))
  return dy < 1 ? 'straight' : 'smoothstep'
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
