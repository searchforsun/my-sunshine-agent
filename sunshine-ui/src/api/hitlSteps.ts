import type { ProcessingStep } from './processingSteps'

export type HitlDecision = 'approved' | 'denied'

export function resolveHitlToken(step: ProcessingStep): string | null {
  const token = step.metadata?.hitlToken
  return typeof token === 'string' && token.trim() ? token.trim() : null
}

export function resolveHitlStatus(step: ProcessingStep): HitlDecision | 'awaiting' | null {
  const status = step.metadata?.hitlStatus
  if (status === 'awaiting' || status === 'approved' || status === 'denied') {
    return status
  }
  return null
}

export function isHitlAwaiting(step: ProcessingStep): boolean {
  return resolveHitlStatus(step) === 'awaiting' && !!resolveHitlToken(step)
}

export function hasHitlPanel(step: ProcessingStep): boolean {
  const status = resolveHitlStatus(step)
  return status === 'awaiting' || status === 'approved' || status === 'denied'
}

export function resolveHitlToolName(step: ProcessingStep): string {
  return step.metadata?.hitlToolDisplayName?.trim()
    || step.label?.trim()
    || '写操作工具'
}

export function resolveHitlHint(step: ProcessingStep): string {
  if (step.summary?.active?.trim()) {
    return step.summary.active.trim()
  }
  return ''
}

export function isToolStepId(stepId: string): boolean {
  return stepId.startsWith('tool-')
}

/** 乐观更新：用户点击确认/取消后立即反映到步骤 metadata（含 node.subSteps） */
export function applyHitlDecision(
  steps: ProcessingStep[],
  token: string,
  approved: boolean,
): ProcessingStep[] {
  const idx = steps.findIndex(s => resolveHitlToken(s) === token)
  if (idx >= 0) {
    const prev = steps[idx]
    const status: HitlDecision = approved ? 'approved' : 'denied'
    const next = [...steps]
    next[idx] = {
      ...prev,
      metadata: {
        ...prev.metadata,
        hitlStatus: status,
        hitlToken: undefined,
      },
    }
    return next
  }
  let changed = false
  const next = steps.map(step => {
    if (!step.subSteps?.length) return step
    const subNext = applyHitlDecision(step.subSteps, token, approved)
    if (subNext === step.subSteps) return step
    changed = true
    return { ...step, subSteps: subNext }
  })
  return changed ? next : steps
}

/** 将 SSE confirmation 合并到当前 running 的工具步骤（含 node.subSteps；主路径为 step.metadata.hitl） */
export function mergeHitlIntoRunningToolStep(
  steps: ProcessingStep[],
  payload: {
    confirmationToken: string
    toolId: string
    toolDisplayName: string
    paramsSummary: string
    expiresAt: number
  },
): ProcessingStep[] {
  const hitlPatch = {
    hitlStatus: 'awaiting' as const,
    hitlToken: payload.confirmationToken,
    hitlToolDisplayName: payload.toolDisplayName,
    hitlParamsSummary: payload.paramsSummary,
    hitlExpiresAt: payload.expiresAt,
  }

  // Plan agent 节点：写工具 HITL 应落在 node.subSteps 内的 tool-* 步
  for (let i = steps.length - 1; i >= 0; i--) {
    const node = steps[i]
    if (!node.id.startsWith('node-') || !node.subSteps?.length) continue
    const subIdx = findHitlTargetToolSubStepIndex(node.subSteps)
    if (subIdx < 0) continue
    const subPrev = node.subSteps[subIdx]
    const subSteps = [...node.subSteps]
    subSteps[subIdx] = {
      ...subPrev,
      metadata: { ...subPrev.metadata, ...hitlPatch },
    }
    const next = [...steps]
    next[i] = { ...node, subSteps, metadata: stripHitlMetadata(node.metadata) }
    return next
  }

  let idx = -1
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    const running = s.lifecycle === 'running' || s.status === 'running'
    if (!running) continue
    if (isToolStepId(s.id)) {
      idx = i
      break
    }
    // 含 subSteps 的 agent 节点禁止把 HITL 挂在 node 自身
    if (s.id.startsWith('node-') && !s.subSteps?.length) {
      idx = i
      break
    }
  }
  if (idx < 0) return steps
  const prev = steps[idx]
  const next = [...steps]
  next[idx] = {
    ...prev,
    metadata: {
      ...prev.metadata,
      ...hitlPatch,
    },
  }
  return next
}

function stripHitlMetadata(meta?: ProcessingStep['metadata']): ProcessingStep['metadata'] | undefined {
  if (!meta) return meta
  const { hitlStatus, hitlToken, hitlToolDisplayName, hitlParamsSummary, hitlExpiresAt, ...rest } = meta
  return Object.keys(rest).length > 0 ? rest : undefined
}

function findHitlTargetToolSubStepIndex(subSteps: ProcessingStep[]): number {
  for (let i = subSteps.length - 1; i >= 0; i--) {
    const s = subSteps[i]
    if (!isToolStepId(s.id)) continue
    const running = s.lifecycle === 'running' || s.status === 'running'
    if (running) return i
  }
  for (let i = subSteps.length - 1; i >= 0; i--) {
    if (isToolStepId(subSteps[i].id)) return i
  }
  return -1
}

/** agent 节点误挂 HITL 时归位到 subSteps 内 tool 步 */
export function relocateAgentNodeHitl(step: ProcessingStep): ProcessingStep {
  if (!step.id.startsWith('node-') || !step.subSteps?.length) return step
  const status = resolveHitlStatus(step)
  if (!status) return step
  if (step.subSteps.some(s => resolveHitlStatus(s) === status)) {
    return { ...step, metadata: stripHitlMetadata(step.metadata) }
  }
  const subIdx = findHitlTargetToolSubStepIndex(step.subSteps)
  if (subIdx < 0) return step
  const hitlPatch = {
    hitlStatus: step.metadata?.hitlStatus,
    hitlToken: step.metadata?.hitlToken,
    hitlToolDisplayName: step.metadata?.hitlToolDisplayName,
    hitlParamsSummary: step.metadata?.hitlParamsSummary,
    hitlExpiresAt: step.metadata?.hitlExpiresAt,
  }
  const subSteps = [...step.subSteps]
  subSteps[subIdx] = {
    ...subSteps[subIdx],
    metadata: { ...subSteps[subIdx].metadata, ...hitlPatch },
  }
  return { ...step, subSteps, metadata: stripHitlMetadata(step.metadata) }
}
