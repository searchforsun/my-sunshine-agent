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
  if (!c?.items?.length) return '未配置条件'
  const logic = c.logic === 'or' ? ' 或 ' : ' 且 '
  return c.items
    .map(item => formatConditionExpr(item.left, item.op, item.right))
    .join(logic)
}

/** 与 exclusive / loop 共用的条件文案 */
export function formatConditionExpr(
  left: string | undefined,
  op: string | undefined,
  right?: string | null,
): string {
  if (!op?.trim()) return '未配置条件'
  const l = left?.trim() || '（左值）'
  const r = right?.trim() ?? ''
  if (op === 'empty') return `${l} 为空`
  if (op === 'not_empty') return `${l} 非空`
  if (op === 'contains') return `${l} 包含「${r}」`
  if (op === 'not_contains') return `${l} 不包含「${r}」`
  if (op === 'eq') return `${l} 等于「${r}」`
  if (op === 'not_eq') return `${l} 不等于「${r}」`
  if (op === 'gt') return `${l} > ${r}`
  if (op === 'lt') return `${l} < ${r}`
  if (op === 'gte') return `${l} ≥ ${r}`
  if (op === 'lte') return `${l} ≤ ${r}`
  return `${l} ${op} ${r}`.trim()
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
