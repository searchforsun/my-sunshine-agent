import type { WorkflowPlan, WorkflowPlanNode } from '../api/workflows'

export type WorkflowLayoutChange = {
  id: string
  label: string
  from: { x: number; y: number } | null
  to: { x: number; y: number } | null
}

export type WorkflowPlanDiffSummary = {
  addedNodes: string[]
  removedNodes: string[]
  changedNodes: { id: string; label: string; fields: string[] }[]
  addedEdges: string[]
  removedEdges: string[]
  reasonChanged: boolean
  changedLayout: WorkflowLayoutChange[]
}

export type WorkflowJsonDiffLine = {
  type: 'unchanged' | 'added' | 'removed'
  text: string
  oldLineNo: number | null
  newLineNo: number | null
}

export type WorkflowSplitDiffRow = {
  left: { lineNo: number | null; text: string; type: WorkflowJsonDiffLine['type'] | 'empty' }
  right: { lineNo: number | null; text: string; type: WorkflowJsonDiffLine['type'] | 'empty' }
}

function edgeKey(e: { from: string; to: string }): string {
  return `${e.from}->${e.to}`
}

function roundCoord(v: number): number {
  return Math.round(v * 10) / 10
}

function nodeSnapshot(n: WorkflowPlanNode): WorkflowPlanNode {
  return {
    id: n.id,
    type: n.type,
    displayName: n.displayName ?? '',
    params: n.params ?? {},
  }
}

function nodeLabelById(plan: WorkflowPlan): Map<string, string> {
  return new Map((plan.nodes ?? []).map(n => [n.id, n.displayName?.trim() || n.id]))
}

function layoutPointEqual(
  a: { x: number; y: number } | null | undefined,
  b: { x: number; y: number } | null | undefined,
): boolean {
  if (!a && !b) return true
  if (!a || !b) return false
  return Math.abs(a.x - b.x) <= 0.5 && Math.abs(a.y - b.y) <= 0.5
}

function diffLayout(from: WorkflowPlan, to: WorkflowPlan): WorkflowLayoutChange[] {
  const fromL = from.layout ?? {}
  const toL = to.layout ?? {}
  const labelById = new Map([...nodeLabelById(from), ...nodeLabelById(to)])
  const ids = new Set([...Object.keys(fromL), ...Object.keys(toL)])
  const changes: WorkflowLayoutChange[] = []
  for (const id of [...ids].sort()) {
    const a = fromL[id] ?? null
    const b = toL[id] ?? null
    if (layoutPointEqual(a, b)) continue
    changes.push({
      id,
      label: labelById.get(id) ?? id,
      from: a ? { x: roundCoord(a.x), y: roundCoord(a.y) } : null,
      to: b ? { x: roundCoord(b.x), y: roundCoord(b.y) } : null,
    })
  }
  return changes
}

function diffNodeFields(from: WorkflowPlanNode, to: WorkflowPlanNode): string[] {
  const fields: string[] = []
  if ((from.displayName ?? '') !== (to.displayName ?? '')) fields.push('displayName')
  if (from.type !== to.type) fields.push('type')
  if (JSON.stringify(from.params ?? {}) !== JSON.stringify(to.params ?? {})) fields.push('params')
  return fields
}

/** 结构化 Plan 差异摘要（含 layout 坐标） */
export function summarizeWorkflowPlanDiff(from: WorkflowPlan, to: WorkflowPlan): WorkflowPlanDiffSummary {
  const fromNodes = new Map((from.nodes ?? []).map(n => [n.id, n]))
  const toNodes = new Map((to.nodes ?? []).map(n => [n.id, n]))
  const addedNodes: string[] = []
  const removedNodes: string[] = []
  const changedNodes: WorkflowPlanDiffSummary['changedNodes'] = []
  for (const [id, node] of toNodes) {
    if (!fromNodes.has(id)) addedNodes.push(id)
    else {
      const fields = diffNodeFields(fromNodes.get(id)!, node)
      if (fields.length > 0) {
        changedNodes.push({
          id,
          label: node.displayName?.trim() || id,
          fields,
        })
      }
    }
  }
  for (const id of fromNodes.keys()) {
    if (!toNodes.has(id)) removedNodes.push(id)
  }
  const fromEdges = new Set((from.edges ?? []).map(edgeKey))
  const toEdges = new Set((to.edges ?? []).map(edgeKey))
  const addedEdges = [...toEdges].filter(k => !fromEdges.has(k))
  const removedEdges = [...fromEdges].filter(k => !toEdges.has(k))
  return {
    addedNodes: addedNodes.sort(),
    removedNodes: removedNodes.sort(),
    changedNodes: changedNodes.sort((a, b) => a.id.localeCompare(b.id)),
    addedEdges: addedEdges.sort(),
    removedEdges: removedEdges.sort(),
    reasonChanged: (from.reason ?? '') !== (to.reason ?? ''),
    changedLayout: diffLayout(from, to),
  }
}

export function formatLayoutPoint(point: { x: number; y: number } | null): string {
  if (!point) return '—'
  return `(${point.x}, ${point.y})`
}

export function hasWorkflowPlanDiff(summary: WorkflowPlanDiffSummary): boolean {
  return summary.addedNodes.length > 0
    || summary.removedNodes.length > 0
    || summary.changedNodes.length > 0
    || summary.addedEdges.length > 0
    || summary.removedEdges.length > 0
    || summary.reasonChanged
    || summary.changedLayout.length > 0
}

function normalizeLayoutForDiff(
  layout?: Record<string, { x: number; y: number }>,
): Record<string, { x: number; y: number }> | undefined {
  if (!layout || Object.keys(layout).length === 0) return undefined
  const out: Record<string, { x: number; y: number }> = {}
  for (const id of Object.keys(layout).sort()) {
    const p = layout[id]
    out[id] = { x: roundCoord(p.x), y: roundCoord(p.y) }
  }
  return out
}

function normalizePlanForJsonDiff(plan: WorkflowPlan): WorkflowPlan {
  const layout = normalizeLayoutForDiff(plan.layout)
  const base: WorkflowPlan = {
    planId: plan.planId ?? null,
    reason: plan.reason ?? '',
    nodes: (plan.nodes ?? []).map(n => nodeSnapshot(n)),
    edges: [...(plan.edges ?? [])],
  }
  if (layout) base.layout = layout
  return base
}

function diffLineArrays(oldLines: string[], newLines: string[]): WorkflowJsonDiffLine[] {
  const m = oldLines.length
  const n = newLines.length
  const dp: number[][] = Array.from({ length: m + 1 }, () => Array(n + 1).fill(0))
  for (let i = m - 1; i >= 0; i -= 1) {
    for (let j = n - 1; j >= 0; j -= 1) {
      dp[i][j] = oldLines[i] === newLines[j]
        ? dp[i + 1][j + 1] + 1
        : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: WorkflowJsonDiffLine[] = []
  let i = 0
  let j = 0
  while (i < m && j < n) {
    if (oldLines[i] === newLines[j]) {
      out.push({
        type: 'unchanged',
        text: oldLines[i],
        oldLineNo: i + 1,
        newLineNo: j + 1,
      })
      i += 1
      j += 1
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'removed', text: oldLines[i], oldLineNo: i + 1, newLineNo: null })
      i += 1
    } else {
      out.push({ type: 'added', text: newLines[j], oldLineNo: null, newLineNo: j + 1 })
      j += 1
    }
  }
  while (i < m) {
    out.push({ type: 'removed', text: oldLines[i], oldLineNo: i + 1, newLineNo: null })
    i += 1
  }
  while (j < n) {
    out.push({ type: 'added', text: newLines[j], oldLineNo: null, newLineNo: j + 1 })
    j += 1
  }
  return out
}

export function diffWorkflowPlanJson(from: WorkflowPlan, to: WorkflowPlan): WorkflowJsonDiffLine[] {
  const oldText = JSON.stringify(normalizePlanForJsonDiff(from), null, 2)
  const newText = JSON.stringify(normalizePlanForJsonDiff(to), null, 2)
  return diffLineArrays(oldText.split('\n'), newText.split('\n'))
}

export function toWorkflowSplitDiffRows(lines: WorkflowJsonDiffLine[]): WorkflowSplitDiffRow[] {
  const rows: WorkflowSplitDiffRow[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (line.type === 'unchanged') {
      rows.push({
        left: { lineNo: line.oldLineNo, text: line.text, type: 'unchanged' },
        right: { lineNo: line.newLineNo, text: line.text, type: 'unchanged' },
      })
      i += 1
      continue
    }
    if (line.type === 'removed') {
      const removed: WorkflowJsonDiffLine[] = []
      while (i < lines.length && lines[i].type === 'removed') {
        removed.push(lines[i])
        i += 1
      }
      const added: WorkflowJsonDiffLine[] = []
      while (i < lines.length && lines[i].type === 'added') {
        added.push(lines[i])
        i += 1
      }
      const max = Math.max(removed.length, added.length, 1)
      for (let k = 0; k < max; k += 1) {
        const r = removed[k]
        const a = added[k]
        rows.push({
          left: r
            ? { lineNo: r.oldLineNo, text: r.text, type: 'removed' }
            : { lineNo: null, text: '', type: 'empty' },
          right: a
            ? { lineNo: a.newLineNo, text: a.text, type: 'added' }
            : { lineNo: null, text: '', type: 'empty' },
        })
      }
      continue
    }
    rows.push({
      left: { lineNo: null, text: '', type: 'empty' },
      right: { lineNo: line.newLineNo, text: line.text, type: 'added' },
    })
    i += 1
  }
  return rows
}

export function workflowDiffPrefix(type: WorkflowJsonDiffLine['type']): string {
  if (type === 'removed') return '- '
  if (type === 'added') return '+ '
  return '  '
}
