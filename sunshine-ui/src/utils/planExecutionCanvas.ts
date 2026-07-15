import type { Node, Edge } from '@vue-flow/core'
import type { PlanGraph } from '../api/executionPlans'
import type { WorkflowPlan } from '../api/workflows'
import type { DagNodeView } from './planGraph'
import { resolveRetryBadgeCount } from './planGraph'
import {
  planToFlowElements,
  type WorkflowFlowNodeData,
} from './workflowFlowProjection'

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

/** 执行态直接使用 execution_plan 中的 layout（SSOT） */
export function workflowPlanForExecution(graph: PlanGraph): WorkflowPlan {
  return planGraphToWorkflowPlan(graph)
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
      data: {
        ...data,
        selected: selectedId === node.id,
        exec,
      },
    }
  })
}
