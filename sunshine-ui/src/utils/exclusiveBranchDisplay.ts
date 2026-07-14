import type { PlanGraph, PlanGraphEdge } from '../api/executionPlans'

export type ExclusiveBranchView = {
  toId: string
  toLabel: string
  isDefault: boolean
  conditionText: string
}

function formatCondition(edge: PlanGraphEdge): string {
  if (edge.default) return '默认分支'
  const c = edge.condition
  if (!c?.op) return '未配置条件'
  const left = c.left?.trim() || '（左值）'
  const right = c.right?.trim() ?? ''
  if (c.op === 'empty') return `${left} 为空`
  if (c.op === 'not_empty') return `${left} 非空`
  if (c.op === 'contains') return `${left} 包含「${right}」`
  if (c.op === 'eq') return `${left} 等于「${right}」`
  return `${left} ${c.op} ${right}`.trim()
}

/** Chat 抽屉：条件分支出边配置（静态；不在画布边标签展示） */
export function resolveExclusiveBranches(
  graph: PlanGraph | null | undefined,
  gatewayId: string | undefined,
): ExclusiveBranchView[] {
  if (!graph?.edges?.length || !gatewayId) return []
  const labelById = new Map(
    (graph.nodes ?? []).map(n => [n.id, n.displayName?.trim() || n.id]),
  )
  return graph.edges
    .filter(e => e.from === gatewayId)
    .map(e => ({
      toId: e.to,
      toLabel: labelById.get(e.to) || e.to,
      isDefault: !!e.default,
      conditionText: formatCondition(e),
    }))
}
