import type { ProcessingStep } from './processingSteps'

export type HitlDecision = 'approved' | 'denied'

/** SSE type:confirmation 载荷 — 与 sseDispatch.ToolConfirmationPayload 对齐 */
export interface HitlConfirmationPayload {
  confirmationToken: string
  toolId: string
  toolDisplayName: string
  paramsSummary: string
  expiresAt: number
}

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

function isHitlResolved(step: ProcessingStep): boolean {
  const status = resolveHitlStatus(step)
  return status === 'approved' || status === 'denied'
}

/** 后端常先发 summary.active，metadata.hitl 与 confirmation 可能晚到 */
export function isHitlSummaryAwaiting(step: ProcessingStep): boolean {
  if (!isHitlToolStep(step)) return false
  if (isHitlResolved(step)) return false
  if (resolveHitlStatus(step) === 'awaiting') return true
  const active = step.summary?.active?.trim() ?? ''
  return active.includes('等待用户确认')
}

export function isHitlAwaiting(step: ProcessingStep): boolean {
  return resolveHitlStatus(step) === 'awaiting' && !!resolveHitlToken(step)
}

export function isToolStepId(stepId: string): boolean {
  return stepId.startsWith('tool-') || stepId.startsWith('tool@')
}

/** ReAct 主 timeline 工具步（id 或 phase 任一命中） */
export function isHitlToolStep(step: ProcessingStep): boolean {
  return isToolStepId(step.id) || step.phase === 'tool'
}

function toolStepIdPrefix(toolId: string): string {
  return `tool-${toolId}`
}

function buildHitlPatch(payload: HitlConfirmationPayload): NonNullable<ProcessingStep['metadata']> {
  const patch: NonNullable<ProcessingStep['metadata']> = {
    hitlStatus: 'awaiting',
    hitlToolDisplayName: payload.toolDisplayName,
    hitlParamsSummary: payload.paramsSummary,
    hitlExpiresAt: payload.expiresAt,
  }
  const token = payload.confirmationToken?.trim()
  if (token) patch.hitlToken = token
  return patch
}

function toolIdFromStepId(stepId: string): string | undefined {
  if (!isToolStepId(stepId)) return undefined
  const raw = stepId.startsWith('tool-') ? stepId.slice(5) : stepId.slice(5)
  const toolId = raw.split('@')[0]?.trim()
  return toolId || undefined
}

/** 从工具步 metadata / summary 推导 pending confirmation（不依赖 type:confirmation 事件） */
export function buildPendingFromStep(step: ProcessingStep): HitlConfirmationPayload | undefined {
  const toolId = toolIdFromStepId(step.id)
  if (!toolId) return undefined
  const awaiting = isHitlAwaiting(step) || isHitlSummaryAwaiting(step)
  if (!awaiting) return undefined
  const token = resolveHitlToken(step) ?? ''
  return {
    confirmationToken: token,
    toolId,
    toolDisplayName: step.metadata?.hitlToolDisplayName?.trim()
      || step.label?.replace(/^调用工具\s*/, '').trim()
      || toolId,
    paramsSummary: step.metadata?.hitlParamsSummary?.trim() ?? '',
    expiresAt: step.metadata?.hitlExpiresAt ?? 0,
  }
}

/** step upsert 后扫描主 timeline 工具步与 agent 节点 subSteps，同步 pending */
export function syncPendingHitlFromSteps(
  steps: ProcessingStep[] | undefined,
): HitlConfirmationPayload | undefined {
  if (!steps?.length) return undefined
  for (let i = steps.length - 1; i >= 0; i--) {
    const pending = buildPendingFromStep(steps[i])
    if (pending) return pending
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    const node = steps[i]
    if (!node.id.startsWith('node-') || !node.subSteps?.length) continue
    for (let j = node.subSteps.length - 1; j >= 0; j--) {
      const pending = buildPendingFromStep(node.subSteps[j])
      if (pending) return pending
    }
  }
  return undefined
}

function mergePendingPayload(
  synced: HitlConfirmationPayload,
  prev?: HitlConfirmationPayload,
): HitlConfirmationPayload {
  return {
    ...synced,
    confirmationToken: synced.confirmationToken || prev?.confirmationToken || '',
    toolDisplayName: synced.toolDisplayName || prev?.toolDisplayName || synced.toolId,
    paramsSummary: synced.paramsSummary || prev?.paramsSummary || '',
    expiresAt: synced.expiresAt || prev?.expiresAt || 0,
  }
}

/** step upsert / confirmation 后统一同步 pending 并合并到 tool 步 */
export function applySyncedPendingHitl(
  steps: ProcessingStep[],
  prev?: HitlConfirmationPayload,
): { steps: ProcessingStep[]; pending?: HitlConfirmationPayload } {
  const synced = syncPendingHitlFromSteps(steps)
  if (!synced) return { steps, pending: undefined }
  const pending = mergePendingPayload(synced, prev)
  return { steps: reapplyPendingHitl(steps, pending), pending }
}

export function hasHitlPanel(step: ProcessingStep): boolean {
  const status = resolveHitlStatus(step)
  return status === 'awaiting' || status === 'approved' || status === 'denied'
}

/** 时间线中是否存在待用户操作的 HITL 步（ReAct 主 timeline 或 agent 节点 subSteps） */
export function stepsHaveAwaitingHitl(steps: ProcessingStep[] | undefined): boolean {
  if (!steps?.length) return false
  if (steps.some(s => isToolStepId(s.id) && (isHitlAwaiting(s) || isHitlSummaryAwaiting(s)))) {
    return true
  }
  for (const node of steps) {
    if (!node.id.startsWith('node-') || !node.subSteps?.length) continue
    if (node.subSteps.some(s => isHitlAwaiting(s) || isHitlSummaryAwaiting(s))) return true
  }
  return false
}

/** timeline :key — token 出现/变化时强制重绘确认框 */
export function resolveHitlUiKey(
  steps: ProcessingStep[] | undefined,
  pending?: HitlConfirmationPayload,
): string {
  if (pending?.confirmationToken?.trim()) return pending.confirmationToken.trim()
  if (!steps?.length) return ''
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (isToolStepId(s.id)) {
      const token = resolveHitlToken(s)
      if (token && (isHitlAwaiting(s) || hasHitlPanel(s))) return token
      if (isHitlSummaryAwaiting(s)) return s.id
    }
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    const node = steps[i]
    if (!node.id.startsWith('node-') || !node.subSteps?.length) continue
    for (let j = node.subSteps.length - 1; j >= 0; j--) {
      const s = node.subSteps[j]
      const token = resolveHitlToken(s)
      if (token && (isHitlAwaiting(s) || hasHitlPanel(s))) return token
      if (isHitlSummaryAwaiting(s)) return s.id
    }
  }
  return ''
}

/** 为工具步匹配尚未落入 metadata 的 pending confirmation */
export function hitlConfirmationForStep(
  step: ProcessingStep,
  payload: HitlConfirmationPayload | undefined,
): HitlConfirmationPayload | undefined {
  if (!payload?.toolId?.trim()) return undefined
  if (isHitlAwaiting(step)) return undefined
  const prefix = toolStepIdPrefix(payload.toolId.trim())
  if (!isToolStepId(step.id) || !step.id.startsWith(prefix)) return undefined
  return payload
}

/** 合并 pending confirmation，供面板展示（metadata 未落步时） */
export function resolveStepForHitlDisplay(
  step: ProcessingStep,
  pending?: HitlConfirmationPayload,
): ProcessingStep {
  if (hasHitlPanel(step)) return step
  const match = hitlConfirmationForStep(step, pending)
  if (!match) return step
  return {
    ...step,
    metadata: { ...step.metadata, ...buildHitlPatch(match) },
  }
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

function isRunningStep(step: ProcessingStep): boolean {
  return step.lifecycle === 'running' || step.status === 'running'
}

/** 乐观更新：用户点击确认/取消后立即反映到 tool 步 metadata */
export function applyHitlDecision(
  steps: ProcessingStep[],
  token: string,
  approved: boolean,
): ProcessingStep[] {
  const topNext = applyHitlDecisionInList(steps, token, approved)
  if (topNext !== steps) return topNext
  let changed = false
  const next = steps.map(step => {
    if (!step.id.startsWith('node-') || !step.subSteps?.length) return step
    const subNext = applyHitlDecisionInList(step.subSteps, token, approved)
    if (subNext === step.subSteps) return step
    changed = true
    return { ...step, subSteps: subNext }
  })
  return changed ? next : steps
}

function applyHitlDecisionInList(
  steps: ProcessingStep[],
  token: string,
  approved: boolean,
): ProcessingStep[] {
  const idx = steps.findIndex(s => resolveHitlToken(s) === token)
  if (idx < 0) return steps
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

/** 将 SSE confirmation 合并到主 timeline 或 agent 节点 subSteps 内 tool 步 */
export function mergeHitlIntoRunningToolStep(
  steps: ProcessingStep[],
  payload: HitlConfirmationPayload,
): ProcessingStep[] {
  const hitlPatch = buildHitlPatch(payload)
  const topIdx = findHitlTargetToolStepIndex(steps, payload.toolId, true)
  if (topIdx >= 0 && isToolStepId(steps[topIdx].id)) {
    const prev = steps[topIdx]
    const next = [...steps]
    next[topIdx] = { ...prev, metadata: { ...prev.metadata, ...hitlPatch } }
    return next
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    const node = steps[i]
    if (!node.id.startsWith('node-') || !node.subSteps?.length) continue
    // 仅挂到当前 running 的 workflow 节点，避免 HITL 落到已完成的前序子 Agent
    if (!isRunningStep(node)) continue
    const subIdx = findHitlTargetToolStepIndex(node.subSteps, payload.toolId, true)
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
  return steps
}

/** 每次 step upsert 后重试：confirmation 可能早于 tool 步骤到达 */
export function reapplyPendingHitl(
  steps: ProcessingStep[],
  payload: HitlConfirmationPayload | undefined,
): ProcessingStep[] {
  if (!payload?.toolId?.trim()) return steps
  const merged = mergeHitlIntoRunningToolStep(steps, payload)
  return merged.map(s => (s.id.startsWith('node-') ? relocateAgentNodeHitl(s) : s))
}

function stripHitlMetadata(meta?: ProcessingStep['metadata']): ProcessingStep['metadata'] | undefined {
  if (!meta) return meta
  const { hitlStatus, hitlToken, hitlToolDisplayName, hitlParamsSummary, hitlExpiresAt, ...rest } = meta
  return Object.keys(rest).length > 0 ? rest : undefined
}

/** attachMode：仅匹配 running 工具步，避免 confirmation 早到误挂前序节点 */
function findHitlTargetToolStepIndex(
  steps: ProcessingStep[],
  toolId?: string,
  attachMode = false,
): number {
  const prefix = toolId?.trim() ? toolStepIdPrefix(toolId.trim()) : null
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (!isToolStepId(s.id)) continue
    if (prefix && !s.id.startsWith(prefix)) continue
    if (isRunningStep(s)) return i
  }
  if (attachMode) return -1
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (!isToolStepId(s.id)) continue
    if (prefix && !s.id.startsWith(prefix)) continue
    if (isHitlResolved(s)) continue
    if (s.lifecycle === 'done' || s.lifecycle === 'skipped') continue
    return i
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    if (isToolStepId(steps[i].id)) return i
  }
  return -1
}

/** 抽屉 / DAG：从全量 steps 取 agent 节点并归位 HITL */
export function resolveAgentNodeStepForDrawer(
  steps: ProcessingStep[] | undefined,
  nodeId: string,
  pending?: HitlConfirmationPayload,
): ProcessingStep | undefined {
  const raw = steps?.find(s => s.id === `node-${nodeId}`)
  if (!raw) return undefined
  let node = relocateAgentNodeHitl(raw)
  if (pending) {
    const merged = reapplyPendingHitl([node], pending)
    node = merged[0] ?? node
  }
  return node
}

/** 子 Agent 执行过程：保证 HITL metadata 落在 tool 子步，供 Plan 抽屉确认框 */
export function resolveAgentSubStepsForDisplay(
  parent: ProcessingStep | undefined,
  pending?: HitlConfirmationPayload,
): ProcessingStep[] {
  if (!parent?.subSteps?.length) return []
  let node = relocateAgentNodeHitl(parent)
  if (pending) {
    const merged = reapplyPendingHitl([node], pending)
    node = merged[0] ?? node
  }
  return node.subSteps ?? []
}

/** agent 节点误挂 HITL 时归位到 subSteps 内 tool 步 */
export function relocateAgentNodeHitl(step: ProcessingStep): ProcessingStep {
  if (!step.id.startsWith('node-') || !step.subSteps?.length) return step
  const status = resolveHitlStatus(step)
  if (!status) return step
  if (step.subSteps.some(s => resolveHitlStatus(s) === status)) {
    return { ...step, metadata: stripHitlMetadata(step.metadata) }
  }
  const subIdx = findHitlTargetToolStepIndex(step.subSteps)
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
