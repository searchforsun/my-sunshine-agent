/** 时间线步骤展示：摘要、展开区、耗时 */
import type { ProcessingStep, StepLifecycle } from './processingSteps'

export interface RewriteDetailView {
  from: string
  to: string
  targetLabel: string
  latencyText?: string
}

export function formatStepLabel(step: ProcessingStep): string {
  if (step.label?.trim()) {
    return step.label
  }
  return step.id
}

export function formatDuration(ms?: number): string {
  if (ms == null) return ''
  if (ms < 1) return '<1ms'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

export function stepLifecycle(step: ProcessingStep): StepLifecycle {
  return (step.lifecycle ?? 'pending') as StepLifecycle
}

export function formatStepMetadata(step: ProcessingStep): string {
  const m = step.metadata
  if (!m) return ''
  const parts: string[] = []
  if (typeof m.hitCount === 'number') {
    parts.push(`命中 ${m.hitCount} 条`)
  }
  const sources = m.sources?.filter(s => s.trim())
  if (sources?.length) {
    parts.push(`来源：${sources.join('、')}`)
  }
  return parts.join('，')
}

export function formatRewriteLatency(latencyMs: number): string {
  return formatDuration(latencyMs) || '<1ms'
}

export function formatRewriteMetadata(step: ProcessingStep): string {
  const m = step.metadata
  if (!m?.rewriteApplied || !m.rewriteFrom || !m.rewriteTo) return ''
  const targetLabel = m.rewriteScenario === 'hyde' ? '参考文档' : '优化后'
  const latency = typeof m.rewriteLatencyMs === 'number'
    ? `\n${formatRewriteLatency(m.rewriteLatencyMs)}`
    : ''
  const body = `原问题：${m.rewriteFrom}\n${targetLabel}：${m.rewriteTo}${latency}`
  if (m.rewriteScenarioLabel?.trim()) {
    return `${m.rewriteScenarioLabel.trim()}\n${body}`
  }
  return body
}

export function resolveRewriteDetail(step: ProcessingStep): RewriteDetailView | undefined {
  const m = step.metadata
  if (m?.rewriteInDetail) return undefined
  if (!m?.rewriteApplied || !m.rewriteFrom || !m.rewriteTo) return undefined
  const targetLabel = m.rewriteScenario === 'hyde' ? '参考文档' : '优化后'
  const latencyText = typeof m.rewriteLatencyMs === 'number'
    ? formatRewriteLatency(m.rewriteLatencyMs)
    : undefined
  return {
    from: m.rewriteFrom,
    to: m.rewriteTo,
    targetLabel,
    latencyText,
  }
}

export const STEP_HEADER_PREVIEW_MAX = 42

function stripPlanMetaText(text: string): string {
  const trimmed = text.trim()
  for (const segment of trimmed.split('|')) {
    const eq = segment.indexOf('=')
    if (eq > 0 && segment.slice(0, eq).trim() === 'chain') {
      return segment.slice(eq + 1).trim()
    }
  }
  return trimmed
}

function truncateStepPreview(text: string, max = STEP_HEADER_PREVIEW_MAX): string {
  if (text.length <= max) return text
  return `${text.slice(0, max)}…`
}

export function isWorkflowAnswerStep(step: ProcessingStep): boolean {
  return step.id === 'node-answer'
}

export function resolveStepSummaryFull(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  const title = formatStepLabel(step)
  let header = ''
  if (lifecycle === 'running') {
    header = step.summary?.active?.trim() || step.label?.trim() || ''
  } else if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    header = step.summary?.after?.trim()
      || formatStepMetadata(step)
      || (!isWorkflowAnswerStep(step) && step.result?.trim())
      || step.detail?.trim()
      || ''
    if (step.phase === 'plan' && header) {
      header = stripPlanMetaText(header)
    }
  } else {
    header = step.summary?.before?.trim() || step.label?.trim() || ''
  }
  if (!header || header === title) {
    return ''
  }
  return header
}

export function resolveStepHeaderText(step: ProcessingStep): string {
  const full = resolveStepSummaryFull(step)
  const oneLine = full.replace(/\s+/g, ' ').trim()
  return truncateStepPreview(oneLine)
}

function extractFirstProseLine(text: string): string {
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#') || /^\|/.test(line)) continue
    if (/^[-*_]{3,}$/.test(line)) continue
    const plain = line.replace(/\*\*|__|`/g, '').replace(/^>\s*/, '').trim()
    if (plain.length >= 8 && /[\u4e00-\u9fff]/.test(plain)) {
      return plain.replace(/\s+/g, ' ')
    }
  }
  return ''
}

export function resolveStepExpandSummary(step: ProcessingStep): string {
  const lifecycle = stepLifecycle(step)
  let oneLine = ''
  if (lifecycle === 'done' || lifecycle === 'error' || lifecycle === 'skipped') {
    oneLine = (step.summary?.after?.trim() || resolveStepSummaryFull(step)).replace(/\s+/g, ' ').trim()
    if (step.phase === 'plan' && oneLine) {
      oneLine = stripPlanMetaText(oneLine)
    }
  } else {
    oneLine = resolveStepSummaryFull(step).replace(/\s+/g, ' ').trim()
  }
  if (oneLine.endsWith('…') && step.detail?.trim()) {
    const fromDetail = extractFirstProseLine(step.detail)
    const prefix = oneLine.slice(0, -1).trim()
    if (fromDetail && (fromDetail.startsWith(prefix) || prefix.length >= 12 && fromDetail.startsWith(prefix.slice(0, 12)))) {
      return fromDetail
    }
  }
  return oneLine
}

export function resolveStepExpandBody(step: ProcessingStep): string {
  if (isWorkflowAnswerStep(step)) {
    return ''
  }
  const summary = resolveStepExpandSummary(step)
  const detail = step.detail?.trim()
  if (detail && detail !== summary) return detail
  const rewrite = formatRewriteMetadata(step)
  if (rewrite && rewrite !== summary) return rewrite
  const result = step.result?.trim()
  if (result && result !== summary && result !== detail) return result
  return ''
}

export function parseLoadedSkillLabel(text?: string): string | undefined {
  if (!text?.trim()) return undefined
  const match = text.trim().match(/^已加载技能：([^\n]+)/)
  const label = match?.[1]?.trim()
  return label || undefined
}

export function stripLoadedSkillPrefix(text?: string): string {
  if (!text?.trim()) return ''
  return text.replace(/^已加载技能：[^\n]+\n\n?/, '').trim()
}

export function shouldShiftSummaryOnExpand(step: ProcessingStep): boolean {
  const summary = resolveStepExpandSummary(step)
  if (!summary) return false
  return summary !== resolveStepHeaderText(step) || summary.length > STEP_HEADER_PREVIEW_MAX
}

export function hasExpandableContent(step: ProcessingStep): boolean {
  if (isWorkflowAnswerStep(step)) {
    return shouldShiftSummaryOnExpand(step)
      || !!formatRewriteMetadata(step)
      || !!step.reasoning?.trim()
      || !!step.output?.trim()
  }
  if (shouldShiftSummaryOnExpand(step)) return true
  if (resolveStepExpandBody(step)) return true
  if (formatRewriteMetadata(step)) return true
  if (step.reasoning?.trim()) return true
  if (step.output?.trim()) return true
  return false
}

export function resolveStepDurationMs(step: ProcessingStep): number | undefined {
  if (step.durationMs != null && step.durationMs >= 0) {
    return step.durationMs
  }
  if (step.startedAt != null && step.endedAt != null && step.endedAt >= step.startedAt) {
    return step.endedAt - step.startedAt
  }
  return undefined
}

export function totalDuration(steps: ProcessingStep[]): number {
  return steps
    .filter(s => s.lifecycle === 'done')
    .reduce((sum, s) => sum + (resolveStepDurationMs(s) ?? 0), 0)
}

export function summarizeSteps(steps: ProcessingStep[]): string {
  const parts = steps
    .filter(s => s.lifecycle === 'done')
    .map(s => {
      if (s.summary?.after) return s.summary.after
      const label = s.label ?? s.id
      return s.detail ? `${label} · ${s.detail}` : label
    })
    .filter(Boolean)
  const total = totalDuration(steps)
  if (total > 0) parts.push(formatDuration(total))
  return parts.join(' · ')
}
