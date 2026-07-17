import type { Node, Edge } from '@vue-flow/core'
import type { PlanGraph } from '../api/executionPlans'
import type { WorkflowPlan } from '../api/workflows'
import type { DagNodeView } from './planGraph'
import { resolveRetryBadgeCount } from './planGraph'
import { computeAutoLayout } from './workflowDagLayout'
import { defaultStartNode } from './workflowPlan'
import {
  planToFlowElements,
  type WorkflowFlowNodeData,
} from './workflowFlowProjection'

/** Plan-Workflow 图仅有 from:"start" 边、无 start 节点时补齐，供布局分层 */
function ensureStartNodeForExecution(plan: WorkflowPlan): WorkflowPlan {
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const hasStartNode = nodes.some(n => n.id === 'start' || n.type === 'start')
  const hasStartEdge = edges.some(e => e.from === 'start')
  if (hasStartNode || !hasStartEdge) return plan
  return { ...plan, nodes: [defaultStartNode(), ...nodes] }
}

/** Chat 执行态只读投影：PlanGraph → Vue Flow；勿从 workflowDagLayout 引入编辑 API。 */
export function planGraphToWorkflowPlan(graph: PlanGraph): WorkflowPlan {
  return {
    planId: graph.planId ?? null,
    reason: graph.reason?.trim() || '',
    nodes: (graph.nodes ?? []).map(n => ({
      id: n.id,
      type: n.type,
      displayName: n.displayName,
      params: n.params,
      ...(n.parentId ? { parentId: n.parentId } : {}),
    })),
    edges: (graph.edges ?? []).map(e => ({
      from: e.from,
      to: e.to,
      ...(e.default ? { default: true } : {}),
      ...(e.condition ? { condition: { ...e.condition } } : {}),
    })),
    layout: graph.layout ? { ...graph.layout } : undefined,
  }
}

/** 执行态：有 layout 用 SSOT；无 layout 时 computeAutoLayout（Plan-Workflow 默认展示） */
export function workflowPlanForExecution(graph: PlanGraph): WorkflowPlan {
  let plan = planGraphToWorkflowPlan(graph)
  plan = ensureStartNodeForExecution(plan)
  if (!plan.layout || Object.keys(plan.layout).length === 0) {
    return { ...plan, layout: computeAutoLayout(plan) }
  }
  return plan
}

export function buildExecutionFlowElements(
  graph: PlanGraph,
  dagNodes: DagNodeView[],
  options?: { selectedId?: string | null; live?: boolean },
) {
  const plan = workflowPlanForExecution(graph)
  const { nodes, edges } = planToFlowElements(plan, options?.selectedId, true)
  return {
    nodes: applyExecOverlay(nodes, dagNodes, options?.live, options?.selectedId),
    edges,
  }
}

/** 仅更新执行态 overlay，不重建节点坐标与边；返回新数组以触发 Vue Flow 重绘 */
export function applyExecOverlay(
  nodes: Node<WorkflowFlowNodeData>[],
  dagNodes: DagNodeView[] | Map<string, DagNodeView>,
  live?: boolean,
  selectedId?: string | null,
): Node<WorkflowFlowNodeData>[] {
  const statusById = dagNodes instanceof Map
    ? dagNodes
    : new Map(dagNodes.map(n => [n.id, n]))
  return nodes.map(node => {
    const data = node.data as WorkflowFlowNodeData | undefined
    if (!data) return node
    const st = statusById.get(node.id)
    const exec = st
      ? {
          status: st.status,
          durationMs: st.durationMs,
          retryBadge: resolveRetryBadgeCount(st),
          live,
          recoveryAwaiting: st.recoveryAwaiting,
        }
      : undefined
    return {
      ...node,
      class: 'nopan',
      data: {
        ...data,
        selected: selectedId === node.id,
        exec,
      },
    }
  })
}
