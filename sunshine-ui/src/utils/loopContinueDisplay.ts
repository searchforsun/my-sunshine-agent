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
  const op = String(params['condition.op'] ?? '').trim()
  if (!op) return []
  return [{
    key: 'continue',
    label: '继续循环',
    value: formatConditionExpr(
      params['condition.left'],
      op,
      params['condition.right'],
    ),
  }]
}
