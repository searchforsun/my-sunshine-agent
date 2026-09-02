import type { ProcessingStep } from './processingSteps'
import { formatStepLabel } from './processingStepsDisplay'

export type HitlDecision = 'approved' | 'denied'

/** SSE type:confirmation 载荷 — 与 sseDispatch.ToolConfirmationPayload 对齐 */
export interface HitlConfirmationPayload {
  confirmationToken: string
  toolId: string
  toolDisplayName: string
  paramsSummary: string
  expiresAt: number
}

/** 无 pending 时的共享空数组：避免每次归一化都新建引用，
 * 保证 OperationStack 在无 HITL 场景下传给子组件的 pending-list 引用稳定、可跳过更新 */
export const EMPTY_PENDING_HITL_LIST: HitlConfirmationPayload[] = []

export function normalizePendingHitlList(
  pending?: HitlConfirmationPayload | HitlConfirmationPayload[] | null,
): HitlConfirmationPayload[] {
  if (!pending) return EMPTY_PENDING_HITL_LIST
  return Array.isArray(pending) ? pending : [pending]
}

export function getPendingHitlConfirmations(
  msg?: { pendingHitlConfirmations?: HitlConfirmationPayload[] } | null,
): HitlConfirmationPayload[] {
  if (!msg) return []
  return msg.pendingHitlConfirmations?.length
    ? msg.pendingHitlConfirmations.map(p => ({ ...p }))
    : []
}

export function setPendingHitlConfirmations(
  msg: { pendingHitlConfirmations?: HitlConfirmationPayload[] },
  list: HitlConfirmationPayload[] | undefined,
): void {
  msg.pendingHitlConfirmations = list?.length ? list.map(p => ({ ...p })) : undefined
}

export function upsertPendingHitlConfirmationList(
  list: HitlConfirmationPayload[],
  item: HitlConfirmationPayload,
): HitlConfirmationPayload[] {
  const token = item.confirmationToken?.trim()
  if (!token) return list
  const next = [...list]
  const idx = next.findIndex(p => p.confirmationToken?.trim() === token)
  if (idx >= 0) next[idx] = { ...next[idx], ...item }
  else next.push({ ...item })
  return next
}

export function removePendingHitlConfirmationList(
  list: HitlConfirmationPayload[],
  token: string,
): HitlConfirmationPayload[] {
  const t = token.trim()
  return list.filter(p => p.confirmationToken?.trim() !== t)
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
  if (!isHitlCarrierStep(step)) return false
  if (isHitlResolved(step)) return false
  if (resolveHitlStatus(step) === 'awaiting') return true
  const active = step.summary?.active?.trim() ?? ''
  const detail = step.detail?.trim() ?? ''
  return active.includes('等待用户确认') || detail.includes('等待用户确认')
}

export function isHitlAwaiting(step: ProcessingStep): boolean {
  return resolveHitlStatus(step) === 'awaiting' && !!resolveHitlToken(step)
}

export function isToolStepId(stepId: string): boolean {
  return stepId.startsWith('tool-') || stepId.startsWith('tool@') || isRagStepId(stepId)
}

/** 去掉 {@code @{epochMs}} 调用后缀，还原 base stepId（与后端 ToolStepIds.stripInvokeSuffix 对齐） */
function stripInvokeSuffix(stepId: string): string {
  const at = stepId.lastIndexOf('@')
  if (at <= 0) return stepId
  if (!/^\d+$/.test(stepId.slice(at + 1))) return stepId
  return stepId.slice(0, at)
}

/** RAG 检索知识库工具步（id 形如 {@code rag@{epochMs}}） */
export function isRagStepId(stepId: string): boolean {
  return stripInvokeSuffix(stepId) === 'rag'
}

/** Plan DAG 业务 node 步（含 tool 写操作 HITL，id 为 node-{id}） */
export function isPlanBizNode(step: ProcessingStep): boolean {
  return step.id.startsWith('node-') && step.id !== 'node-answer'
}

/** 可承载 HITL 的步骤：ReAct tool 步或 Plan DAG 业务 node */
export function isHitlCarrierStep(step: ProcessingStep): boolean {
  return isHitlToolStep(step) || isPlanBizNode(step)
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

function buildPendingFromPlanNode(step: ProcessingStep): HitlConfirmationPayload | undefined {
  if (!isPlanBizNode(step)) return undefined
  const awaiting = isHitlAwaiting(step) || isHitlSummaryAwaiting(step)
  if (!awaiting) return undefined
  const token = resolveHitlToken(step) ?? ''
  const toolDisplayName = step.metadata?.hitlToolDisplayName?.trim()
    || formatStepLabel(step)
    || '写操作工具'
  return {
    confirmationToken: token,
    toolId: toolDisplayName,
    toolDisplayName,
    paramsSummary: step.metadata?.hitlParamsSummary?.trim() ?? '',
    expiresAt: step.metadata?.hitlExpiresAt ?? 0,
  }
}

/** 从工具步 metadata / summary 推导 pending confirmation（不依赖 type:confirmation 事件） */
export function buildPendingFromStep(step: ProcessingStep): HitlConfirmationPayload | undefined {
  const fromPlan = buildPendingFromPlanNode(step)
  if (fromPlan) return fromPlan
  const toolId = toolIdFromStepId(step.id)
  if (!toolId) return undefined
  const awaiting = isHitlAwaiting(step) || isHitlSummaryAwaiting(step)
  if (!awaiting) return undefined
  const token = resolveHitlToken(step) ?? ''
  return {
    confirmationToken: token,
    toolId,
    toolDisplayName: step.metadata?.hitlToolDisplayName?.trim()
      || formatStepLabel(step).replace(/^调用工具\s*/, '').trim()
      || toolId,
    paramsSummary: step.metadata?.hitlParamsSummary?.trim() ?? '',
    expiresAt: step.metadata?.hitlExpiresAt ?? 0,
  }
}

function toolStepPrefixFromId(stepId: string): string {
  const at = stepId.indexOf('@')
  return at > 0 ? stepId.slice(0, at) : stepId
}

function walkAllSteps(steps: ProcessingStep[], visit: (step: ProcessingStep) => void): void {
  for (const step of steps) {
    visit(step)
    if (step.subSteps?.length) walkAllSteps(step.subSteps, visit)
  }
}

function collectBoundHitlTokens(steps: ProcessingStep[]): Set<string> {
  const bound = new Set<string>()
  walkAllSteps(steps, step => {
    const token = resolveHitlToken(step)?.trim()
    if (token) bound.add(token)
  })
  return bound
}

/** 按时间线顺序收集仍待 metadata/token 的 HITL 承载步 */
export function collectAwaitingHitlCarriers(steps: ProcessingStep[]): ProcessingStep[] {
  const out: ProcessingStep[] = []
  walkAllSteps(steps, step => {
    if (!isHitlCarrierStep(step)) return
    if (isHitlResolved(step)) return
    if (resolveHitlToken(step)) return
    if (isHitlAwaiting(step) || isHitlSummaryAwaiting(step) || hasHitlPanel(step)) {
      out.push(step)
    }
  })
  return out
}

/** 为单步解析对应 pending（按 token 或同工具未绑定顺序匹配） */
export function resolvePendingHitlForStep(
  step: ProcessingStep,
  pendingList: HitlConfirmationPayload[],
  allSteps: ProcessingStep[],
): HitlConfirmationPayload | undefined {
  if (!pendingList.length) return undefined
  const stepToken = resolveHitlToken(step)?.trim()
  if (stepToken) {
    return pendingList.find(p => p.confirmationToken?.trim() === stepToken)
  }
  if (isHitlResolved(step)) return undefined
  if (!isHitlSummaryAwaiting(step) && !isHitlAwaiting(step) && !hasHitlPanel(step)) {
    return undefined
  }
  const bound = collectBoundHitlTokens(allSteps)
  const orphans = pendingList.filter(p => {
    const token = p.confirmationToken?.trim()
    if (!token || bound.has(token)) return false
    if (isToolStepId(step.id)) {
      return step.id.startsWith(toolStepIdPrefix(p.toolId.trim()))
    }
    if (isPlanBizNode(step)) {
      return p.toolId.trim() === (step.metadata?.hitlToolDisplayName?.trim() || formatStepLabel(step))
    }
    return false
  })
  if (!orphans.length) return undefined
  const awaitingPeers = collectAwaitingHitlCarriers(allSteps).filter(peer => {
    if (isToolStepId(step.id) && isToolStepId(peer.id)) {
      return toolStepPrefixFromId(peer.id) === toolStepPrefixFromId(step.id)
    }
    return peer.id === step.id
  })
  const idx = awaitingPeers.findIndex(peer => peer.id === step.id)
  return idx >= 0 ? orphans[idx] : orphans[0]
}

/** 从 steps metadata 同步全部 awaiting pending */
export function syncPendingHitlListFromSteps(steps: ProcessingStep[] | undefined): HitlConfirmationPayload[] {
  if (!steps?.length) return []
  const out: HitlConfirmationPayload[] = []
  const seen = new Set<string>()
  walkAllSteps(steps, step => {
    if (isHitlResolved(step)) return
    const pending = buildPendingFromStep(step) ?? buildPendingFromPlanNode(step)
    const token = pending?.confirmationToken?.trim()
    if (!pending || !token || seen.has(token)) return
    seen.add(token)
    out.push(pending)
  })
  return out
}

function mergePendingLists(
  fromSteps: HitlConfirmationPayload[],
  prev: HitlConfirmationPayload[],
): HitlConfirmationPayload[] {
  let merged = [...fromSteps]
  for (const item of prev) {
    merged = upsertPendingHitlConfirmationList(merged, item)
  }
  return merged
}

/** step upsert / confirmation 后统一同步 pending 并合并到 tool 步 */
export function applySyncedPendingHitl(
  steps: ProcessingStep[],
  prev?: HitlConfirmationPayload | HitlConfirmationPayload[],
): { steps: ProcessingStep[]; pending?: HitlConfirmationPayload[] } {
  const prevList = normalizePendingHitlList(prev)
  let nextSteps = reapplyPendingHitlList(steps, prevList)
  const fromSteps = syncPendingHitlListFromSteps(nextSteps)
  const merged = mergePendingLists(fromSteps, prevList)
  const orphans = merged.filter(p => {
    const token = p.confirmationToken?.trim()
    if (!token) return false
    return !collectBoundHitlTokens(nextSteps).has(token)
  })
  if (orphans.length) {
    nextSteps = reapplyPendingHitlList(nextSteps, orphans)
  }
  const pending = syncPendingHitlListFromSteps(nextSteps)
  const stillOrphans = orphans.filter(p => {
    const token = p.confirmationToken?.trim()
    return token && !collectBoundHitlTokens(nextSteps).has(token)
  })
  const finalPending = mergePendingLists(pending, stillOrphans)
  return { steps: nextSteps, pending: finalPending.length ? finalPending : undefined }
}

export function hasHitlPanel(step: ProcessingStep): boolean {
  const status = resolveHitlStatus(step)
  return status === 'awaiting' || status === 'approved' || status === 'denied'
}

/** 时间线中是否存在待用户操作的 HITL 步（ReAct 主 timeline 或 agent 节点 subSteps） */
export function stepsHaveAwaitingHitl(steps: ProcessingStep[] | undefined): boolean {
  if (!steps?.length) return false
  if (steps.some(s => isPlanBizNode(s) && (isHitlAwaiting(s) || isHitlSummaryAwaiting(s)))) {
    return true
  }
  if (steps.some(s => isToolStepId(s.id) && (isHitlAwaiting(s) || isHitlSummaryAwaiting(s)))) {
    return true
  }
  for (const node of steps) {
    if (!node.subSteps?.length) continue
    if (!node.id.startsWith('node-') && !node.id.startsWith('subagent-') && node.phase !== 'subagent') {
      continue
    }
    if (node.subSteps.some(s => isHitlAwaiting(s) || isHitlSummaryAwaiting(s))) return true
  }
  return false
}

/** timeline :key — token 出现/变化时强制重绘确认框 */
export function resolveHitlUiKey(
  steps: ProcessingStep[] | undefined,
  pending?: HitlConfirmationPayload | HitlConfirmationPayload[],
): string {
  const tokens = new Set<string>()
  for (const p of syncPendingHitlListFromSteps(steps)) {
    const t = p.confirmationToken?.trim()
    if (t) tokens.add(t)
  }
  for (const p of normalizePendingHitlList(pending)) {
    const t = p.confirmationToken?.trim()
    if (t) tokens.add(t)
  }
  if (tokens.size) return [...tokens].join('|')
  if (!steps?.length) return ''
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (isPlanBizNode(s) && isHitlSummaryAwaiting(s)) return s.id
    if (isToolStepId(s.id) && isHitlSummaryAwaiting(s)) return s.id
  }
  return ''
}

/** 为工具步匹配尚未落入 metadata 的 pending confirmation */
export function hitlConfirmationForStep(
  step: ProcessingStep,
  payload: HitlConfirmationPayload | HitlConfirmationPayload[] | undefined,
  allSteps?: ProcessingStep[],
): HitlConfirmationPayload | undefined {
  const list = normalizePendingHitlList(payload)
  if (!list.length) return undefined
  const roots = allSteps ?? [step]
  return resolvePendingHitlForStep(step, list, roots)
}

/** 合并 pending confirmation，供面板展示（metadata 未落步时） */
export function resolveStepForHitlDisplay(
  step: ProcessingStep,
  pending?: HitlConfirmationPayload | HitlConfirmationPayload[],
  allSteps?: ProcessingStep[],
): ProcessingStep {
  if (hasHitlPanel(step)) return step
  const roots = allSteps ?? [step]
  const match = resolvePendingHitlForStep(step, normalizePendingHitlList(pending), roots)
  if (!match) return step
  return {
    ...step,
    metadata: { ...step.metadata, ...buildHitlPatch(match) },
  }
}

export function resolveHitlToolName(step: ProcessingStep): string {
  return step.metadata?.hitlToolDisplayName?.trim()
    || formatStepLabel(step)
    || '写操作工具'
}

/** 解析 HITL 参数摘要为 key/value 对（确认框不展示正文类参数） */
const HITL_BODY_PARAM_KEYS = new Set(['content', 'new_string', 'old_string', 'command'])

export function parseHitlParamsSummary(raw?: string | null, maxValueLen = 120): { key: string; value: string }[] {
  if (!raw?.trim()) return []
  const pairs: { key: string; value: string }[] = []
  for (const segment of raw.split(/,\s*(?=[\w.-]+=)/)) {
    const eq = segment.indexOf('=')
    if (eq <= 0) continue
    const key = segment.slice(0, eq).trim()
    let val = segment.slice(eq + 1).trim()
    if (!key || HITL_BODY_PARAM_KEYS.has(key)) continue
    if (val.length > maxValueLen) val = `${val.slice(0, maxValueLen)}…`
    pairs.push({ key, value: val })
  }
  return pairs
}

/** HITL 参数摘要：仅展示 key=value，过长值截断（确认框专用，非业务正文） */
export function formatHitlParamsSummary(raw?: string | null, maxValueLen = 120): string {
  return parseHitlParamsSummary(raw, maxValueLen)
    .map(({ key, value }) => `${key}=${value}`)
    .join(', ')
}

function isRunningStep(step: ProcessingStep): boolean {
  return step.lifecycle === 'running'
}

/** 按 token / pending 载荷定位应写入决策的工具步（同工具多次调用） */
export function resolveHitlTargetStepIndex(
  steps: ProcessingStep[],
  token: string,
  pendingList: HitlConfirmationPayload[] = [],
): number {
  const trimmed = token.trim()
  if (!trimmed) return -1
  for (let i = steps.length - 1; i >= 0; i--) {
    if (resolveHitlToken(steps[i]) === trimmed) return i
  }
  const payload = pendingList.find(p => p.confirmationToken?.trim() === trimmed)
  if (!payload) return -1
  let idx = findHitlTargetToolStepIndex(steps, payload, false)
  if (idx < 0) idx = findHitlTargetToolStepIndex(steps, payload, true)
  return idx
}

/** 乐观更新：用户点击确认/取消后立即反映到 tool 步 metadata */
export function applyHitlDecision(
  steps: ProcessingStep[],
  token: string,
  approved: boolean,
  pending?: HitlConfirmationPayload | HitlConfirmationPayload[],
): ProcessingStep[] {
  const pendingList = normalizePendingHitlList(pending)
  const topNext = applyHitlDecisionInList(steps, token, approved, pendingList)
  if (topNext !== steps) return topNext
  let changed = false
  const next = steps.map(step => {
    if (!step.id.startsWith('node-') || !step.subSteps?.length) return step
    const subNext = applyHitlDecisionInList(step.subSteps, token, approved, pendingList)
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
  pendingList: HitlConfirmationPayload[],
): ProcessingStep[] {
  const idx = resolveHitlTargetStepIndex(steps, token, pendingList)
  if (idx < 0) return steps
  const prev = steps[idx]
  const status: HitlDecision = approved ? 'approved' : 'denied'
  const payload = pendingList.find(p => p.confirmationToken?.trim() === token.trim())
  const next = [...steps]
  next[idx] = {
    ...prev,
    metadata: {
      ...prev.metadata,
      hitlStatus: status,
      hitlToken: undefined,
      hitlToolDisplayName: prev.metadata?.hitlToolDisplayName ?? payload?.toolDisplayName,
      hitlParamsSummary: prev.metadata?.hitlParamsSummary ?? payload?.paramsSummary,
      hitlExpiresAt: prev.metadata?.hitlExpiresAt ?? payload?.expiresAt,
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
  const planIdx = findHitlTargetPlanNodeIndex(steps, true)
  if (planIdx >= 0) {
    const prev = steps[planIdx]
    const next = [...steps]
    next[planIdx] = { ...prev, metadata: { ...prev.metadata, ...hitlPatch } }
    return next
  }
  const topIdx = findHitlTargetToolStepIndex(steps, payload, true)
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
    const subIdx = findHitlTargetToolStepIndex(node.subSteps, payload, true)
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
export function reapplyPendingHitlList(
  steps: ProcessingStep[],
  payloads: HitlConfirmationPayload[],
): ProcessingStep[] {
  let next = steps
  for (const payload of payloads) {
    if (!payload?.toolId?.trim()) continue
    next = mergeHitlIntoRunningToolStep(next, payload)
    next = next.map(s => (s.id.startsWith('node-') ? relocateAgentNodeHitl(s) : s))
  }
  return next
}

export function reapplyPendingHitl(
  steps: ProcessingStep[],
  payload: HitlConfirmationPayload | undefined,
): ProcessingStep[] {
  if (!payload?.toolId?.trim()) return steps
  return reapplyPendingHitlList(steps, [payload])
}

function stripHitlMetadata(meta?: ProcessingStep['metadata']): ProcessingStep['metadata'] | undefined {
  if (!meta) return meta
  const { hitlStatus, hitlToken, hitlToolDisplayName, hitlParamsSummary, hitlExpiresAt, ...rest } = meta
  return Object.keys(rest).length > 0 ? rest : undefined
}

/** attachMode：仅匹配 running/paused 且待确认的 Plan 业务 node */
function findHitlTargetPlanNodeIndex(
  steps: ProcessingStep[],
  attachMode = false,
): number {
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (!isPlanBizNode(s)) continue
    if (attachMode) {
      const lc = s.lifecycle
      if (lc !== 'running' && lc !== 'paused' && lc !== 'pending') continue
    }
    if (isHitlResolved(s)) continue
    if (isHitlAwaiting(s) || isHitlSummaryAwaiting(s) || hasHitlPanel(s)) return i
    if (attachMode && s.lifecycle === 'running') return i
  }
  if (attachMode) return -1
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (!isPlanBizNode(s)) continue
    if (isHitlResolved(s)) continue
    if (s.lifecycle === 'done' || s.lifecycle === 'skipped') continue
    return i
  }
  return -1
}

/** attachMode：优先 running/paused 且无 token 的工具步，避免同工具多次确认串步 */
function findHitlTargetToolStepIndex(
  steps: ProcessingStep[],
  payload: HitlConfirmationPayload,
  attachMode = false,
): number {
  const toolId = payload.toolId?.trim()
  const token = payload.confirmationToken?.trim()
  const prefix = toolId ? toolStepIdPrefix(toolId) : null
  const matchesTool = (s: ProcessingStep) => {
    if (!isToolStepId(s.id)) return false
    if (prefix && !s.id.startsWith(prefix)) return false
    return true
  }
  if (token) {
    for (let i = steps.length - 1; i >= 0; i--) {
      const s = steps[i]
      if (matchesTool(s) && resolveHitlToken(s) === token) return i
    }
  }
  if (attachMode) {
    for (let i = steps.length - 1; i >= 0; i--) {
      const s = steps[i]
      if (!matchesTool(s)) continue
      const lc = s.lifecycle
      if (lc !== 'running' && lc !== 'paused') continue
      if (resolveHitlToken(s)) continue
      return i
    }
    return -1
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (!matchesTool(s)) continue
    if (isRunningStep(s)) return i
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    const s = steps[i]
    if (!matchesTool(s)) continue
    if (isHitlResolved(s)) continue
    if (s.lifecycle === 'done' || s.lifecycle === 'skipped') continue
    return i
  }
  for (let i = steps.length - 1; i >= 0; i--) {
    if (matchesTool(steps[i])) return i
  }
  return -1
}

function findAnyHitlToolStepIndex(steps: ProcessingStep[]): number {
  for (let i = steps.length - 1; i >= 0; i--) {
    if (isToolStepId(steps[i].id)) return i
  }
  return -1
}

/** 抽屉 / DAG：从全量 steps 取 agent 节点并归位 HITL（含 loop.subSteps 的 i{n}-node-*） */
export function resolveAgentNodeStepForDrawer(
  steps: ProcessingStep[] | undefined,
  nodeId: string,
  pending?: HitlConfirmationPayload | HitlConfirmationPayload[],
): ProcessingStep | undefined {
  const raw = findAgentNodeStep(steps, nodeId)
  if (!raw) return undefined
  let node = relocateAgentNodeHitl(raw)
  const list = normalizePendingHitlList(pending)
  if (list.length) {
    const merged = reapplyPendingHitlList([node], list)
    node = merged[0] ?? node
  }
  return node
}

function findAgentNodeStep(
  steps: ProcessingStep[] | undefined,
  nodeId: string,
): ProcessingStep | undefined {
  // ReAct spawn_subagent：抽屉用 step.id（subagent-{runId}）作 node.id
  if (nodeId.startsWith('subagent-')) {
    return steps?.find(s => s.id === nodeId)
  }
  // Planner-Executor worker：抽屉用 step.id（worker-{taskId}）作 node.id
  if (nodeId.startsWith('worker-')) {
    return steps?.find(s => s.id === nodeId)
  }
  const top = steps?.find(s => s.id === `node-${nodeId}`)
  if (top) return top
  const suffix = `node-${nodeId}`
  let best: ProcessingStep | undefined
  let bestRound = -1
  for (const parent of steps ?? []) {
    for (const sub of parent.subSteps ?? []) {
      const m = /^i(\d+)-(.*)$/.exec(sub.id ?? '')
      if (!m || m[2] !== suffix) continue
      const round = Number(m[1])
      if (round >= bestRound) {
        bestRound = round
        best = sub
      }
    }
  }
  return best
}

/** agent 节点误挂 HITL 时归位到 subSteps 内 tool 步（含 loop 内 i{n}-node-*） */
export function relocateAgentNodeHitl(step: ProcessingStep): ProcessingStep {
  if (!isAgentOrLoopBodyNodeId(step.id) || !step.subSteps?.length) return step
  const status = resolveHitlStatus(step)
  if (!status) return step
  if (step.subSteps.some(s => resolveHitlStatus(s) === status)) {
    return { ...step, metadata: stripHitlMetadata(step.metadata) }
  }
  const subIdx = findAnyHitlToolStepIndex(step.subSteps)
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

function isAgentOrLoopBodyNodeId(id: string): boolean {
  return id.startsWith('node-') || /^i\d+-node-/.test(id)
}

/** ReAct HITL 续跑：paused 工具步恢复 running，保留 metadata 供后端 re-await */
export function reactivatePausedReactHitlSteps(steps: ProcessingStep[] | undefined): ProcessingStep[] {
  if (!steps?.length) return steps ?? []
  return steps.map(step => {
    const lc = step.lifecycle
    if (lc !== 'paused' || !isHitlToolStep(step)) return step
    if (!isHitlAwaiting(step) && !isHitlSummaryAwaiting(step)) return step
    return {
      ...step,
      lifecycle: 'running',
      summary: {
        ...step.summary,
        active: step.summary?.active?.includes('暂停')
          ? '等待用户确认执行写操作'
          : (step.summary?.active ?? '等待用户确认执行写操作'),
        after: undefined,
      },
      endedAt: undefined,
    }
  })
}
