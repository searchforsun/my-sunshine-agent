/** Plan 步骤 detail/after 解析（planId、chain、replan） */
import type { ProcessingStep } from './processingSteps'

export interface PlanStepDetailView {
  planId?: string
  chain?: string
  chainSteps: string[]
  replanCount?: number
}

function parsePlanStepDetailText(text: string): PlanStepDetailView {
  const trimmed = text.trim()
  const parts: Record<string, string> = {}
  let hasKeyedParts = false
  for (const segment of trimmed.split('|')) {
    const eq = segment.indexOf('=')
    if (eq <= 0) continue
    hasKeyedParts = true
    parts[segment.slice(0, eq).trim()] = segment.slice(eq + 1).trim()
  }
  let chain = parts.chain
  if (!hasKeyedParts) {
    chain = trimmed
  }
  const chainSteps = chain
    ? chain.split(/\s*→\s*/).map(s => s.trim()).filter(Boolean)
    : []
  const replanParsed = parts.replanCount != null ? Number.parseInt(parts.replanCount, 10) : Number.NaN
  const replanCount = Number.isFinite(replanParsed) && replanParsed > 0 ? replanParsed : undefined
  return {
    planId: parts.planId,
    chain,
    chainSteps,
    replanCount,
  }
}

export function parsePlanStepMeta(text?: string): { planId?: string; chain?: string } {
  if (!text?.trim()) return {}
  const parsed = parsePlanStepDetailText(text.trim())
  if (parsed.planId || parsed.chain) {
    return { planId: parsed.planId, chain: parsed.chain }
  }
  return { chain: text.trim() }
}

/** 解析 plan 步 detail/after，供「开始」节点抽屉结构化展示 */
export function resolvePlanStepDetail(step: ProcessingStep): PlanStepDetailView {
  for (const source of [step.detail, step.summary?.after]) {
    if (!source?.trim()) continue
    const parsed = parsePlanStepDetailText(source)
    if (parsed.planId || parsed.chainSteps.length > 0 || parsed.replanCount != null) {
      return parsed
    }
  }
  return { chainSteps: [] }
}

export function resolvePlanIdFromStep(step: ProcessingStep): string | undefined {
  for (const source of [step.detail, step.summary?.after, step.result]) {
    const id = parsePlanStepMeta(source)?.planId
    if (id) return id
  }
  return undefined
}
