import type { WorkflowPlan, WorkflowPlanNode } from '../api/workflows'
import {
  defaultDisplayName,
  defaultParamsForType,
  nextBusinessNodeId,
  reconcilePlanDataFlow,
  type WorkflowBusinessNodeType,
} from './workflowPlan'
import { buildRetryParams, resolveNodeDefaults } from './workflowNodeParams'
import { isLoopType } from './workflowGateway'
import {
  BUSINESS_NODE_WIDTH,
  LOOP_INNER_GAP,
  LOOP_PAD_TOP,
  LOOP_PAD_X,
  ORIGIN_X,
  ORIGIN_Y,
  measureLoopSize,
  type WorkflowLayoutPos,
} from './workflowDagLayoutMetrics'

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

export function layoutLoopBody(
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

export function addLoopBodyNode(
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
  const positions = { ...(draft.layout ?? {}) }
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

export function addLoopContainer(
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
