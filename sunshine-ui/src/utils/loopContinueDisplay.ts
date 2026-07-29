import type { ProcessingStep } from '../api/processingSteps'
import { formatConditionExpr } from './exclusiveBranchDisplay'

export type LoopContinueRow = {
  key: string
  label: string
  value: string
}

/** Chat 抽屉：仅展示继续条件文案（与条件分支同风格） */
export function resolveLoopContinueRows(
  params: Record<string, string> | undefined,
  _step?: ProcessingStep | null,
): LoopContinueRow[] {
  if (!params) return []
  const conditions = params.conditions as unknown
  if (!Array.isArray(conditions)) return []
  const logic = params.conditionLogic === 'or' ? 'or' : 'and'
  const rows: LoopContinueRow[] = []
  for (const [i, c] of conditions.entries()) {
    const item = c as Record<string, string>
    const op = String(item?.op ?? '').trim()
    const left = String(item?.left ?? '').trim()
    if (!op || !left) continue
    rows.push({
      key: `continue-${i}`,
      label: logic === 'or' ? '继续循环（任一）' : '继续循环',
      value: formatConditionExpr(left, op, String(item?.right ?? '')),
    })
  }
  return rows
}
