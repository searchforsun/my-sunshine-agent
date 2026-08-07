/**
 * 正文穿插：
 * - ReAct：content_start → content(segmentId) → content_end
 * - Plan answer / legacy：plain content 自动锚定 timeline 末步，渲染时 node-* 回退到 plan
 */
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'
import { formatStepLabel } from './processingStepsDisplay'
import { isThinkStepId } from './processingStepsNormalize'

function mergeStreamChunk(existing: string, chunk: string): string {
  const maxOverlap = Math.min(existing.length, chunk.length, 64)
  for (let n = maxOverlap; n > 0; n--) {
    if (existing.endsWith(chunk.slice(0, n))) return existing + chunk.slice(n)
  }
  return existing + chunk
}

export interface ContentBlock {
  /** ReAct 分段 id；Plan 自动锚定为 tail:{stepId} */
  segmentId: string
  /** 正文穿插在该步骤之后（含隐藏 node-answer） */
  afterStepId: string
  text: string
}

/** ReAct 正文已 inline 穿插，隐藏 generate 步骤行（后端也不再下发） */
export function isHiddenReactTimelineStep(step: ProcessingStep): boolean {
  return step.id === 'generate' || step.phase === 'generate'
}

function appendMessageContent(msg: ChatMessage, chunk: string, resume: boolean): void {
  msg.content = resume
    ? mergeStreamChunk(msg.content ?? '', chunk)
    : (msg.content ?? '') + chunk
}

function findBlock(blocks: ContentBlock[] | undefined, segmentId: string): ContentBlock | undefined {
  return blocks?.find(b => b.segmentId === segmentId)
}

/** 隐藏步的正文块改挂到 timeline 中前一个可见步骤之后 */
export function resolveVisibleContentAnchor(
  afterStepId: string,
  steps: ProcessingStep[],
  visibleStepIds: ReadonlySet<string>,
): string | null {
  if (visibleStepIds.has(afterStepId)) return afterStepId
  const idx = steps.findIndex(s => s.id === afterStepId)
  if (idx < 0) return afterStepId
  for (let i = idx - 1; i >= 0; i--) {
    if (visibleStepIds.has(steps[i].id)) return steps[i].id
  }
  return null
}

/** Plan answer 等：正文锚定 timeline 排序后的最后一步 */
export function resolveContentAnchorStepId(steps: ProcessingStep[]): string | null {
  if (!steps.length) return null
  return steps[steps.length - 1].id
}

/** 新增步骤排在既有正文锚点之后时，将非 ReAct 分段块整体挪到 timeline 末尾 */
export function maybeReanchorContentBlocksToTail(
  steps: ProcessingStep[],
  blocks: ContentBlock[] | undefined,
): void {
  if (!blocks?.length || !steps.length) return
  const lastStepId = steps[steps.length - 1].id
  let maxAnchorIdx = -1
  for (const block of blocks) {
    if (block.segmentId.startsWith('content-')) continue
    const idx = steps.findIndex(s => s.id === block.afterStepId)
    if (idx >= 0) maxAnchorIdx = Math.max(maxAnchorIdx, idx)
  }
  const lastIdx = steps.length - 1
  if (lastIdx <= maxAnchorIdx) return
  for (const block of blocks) {
    if (block.segmentId.startsWith('content-')) continue
    block.afterStepId = lastStepId
    block.segmentId = `tail:${lastStepId}`
  }
}

/** ReAct：content_start */
export function beginContentSegment(msg: ChatMessage, segmentId: string, afterStepId: string): void {
  if (!segmentId || !afterStepId) return
  if (!msg.contentBlocks) msg.contentBlocks = []
  if (findBlock(msg.contentBlocks, segmentId)) return
  msg.contentBlocks.push({ segmentId, afterStepId, text: '' })
}

/** ReAct：段内 content */
export function appendSegmentContent(
  msg: ChatMessage,
  segmentId: string,
  chunk: string,
  resume: boolean,
): void {
  if (!chunk || !segmentId) return
  const block = findBlock(msg.contentBlocks, segmentId)
  if (!block) return
  appendMessageContent(msg, chunk, resume)
  block.text = resume ? mergeStreamChunk(block.text, chunk) : block.text + chunk
}

/** ReAct：content_end */
export function endContentSegment(_msg: ChatMessage, _segmentId: string): void {
  // no-op
}

/** 子 Agent node 步：content_start */
export function beginStepContentSegment(step: ProcessingStep, segmentId: string, afterStepId: string): void {
  if (!segmentId || !afterStepId) return
  if (!step.contentBlocks) step.contentBlocks = []
  if (findBlock(step.contentBlocks, segmentId)) return
  step.contentBlocks.push({ segmentId, afterStepId, text: '' })
}

/** 子 Agent node 步：段内 content */
export function appendStepSegmentContent(
  step: ProcessingStep,
  segmentId: string,
  chunk: string,
  resume: boolean,
): void {
  if (!chunk || !segmentId) return
  const block = findBlock(step.contentBlocks, segmentId)
  if (!block) return
  block.text = resume ? mergeStreamChunk(block.text, chunk) : block.text + chunk
}

/** 子 Agent node 步：content_end */
export function endStepContentSegment(_step: ProcessingStep, _segmentId: string): void {
  // no-op
}

/**
 * plain content：锚定 afterStepId 或自动解析末步（Plan answer 含 node-answer）
 */
export function appendInterleavedContent(
  msg: ChatMessage,
  chunk: string,
  afterStepId: string | null | undefined,
  resume: boolean,
): void {
  if (!chunk) return
  const steps = msg.steps
  // answer / plan 正文 SSOT：node-answer.result（step_delta）；plain content 会破坏表格换行
  if (steps?.some(s => s.phase === 'plan' || s.id === 'node-answer')) return
  appendMessageContent(msg, chunk, resume)
  if (!steps?.length) return
  if (!msg.contentBlocks) msg.contentBlocks = []
  const blocks = msg.contentBlocks
  const anchor = afterStepId || resolveContentAnchorStepId(steps)
  if (!anchor) return
  const tailId = `tail:${anchor}`
  const last = blocks[blocks.length - 1]
  if (last && last.segmentId === tailId && last.afterStepId === anchor) {
    last.text = resume ? mergeStreamChunk(last.text, chunk) : last.text + chunk
  } else {
    blocks.push({ segmentId: tailId, afterStepId: anchor, text: chunk })
  }
}

export function joinedContentBlocks(blocks: ContentBlock[] | undefined): string {
  return blocks?.map(b => b.text).join('') ?? ''
}

function joinedPlanAnswerBlocks(blocks: ContentBlock[] | undefined): string {
  if (!blocks?.length) return ''
  return blocks
    .filter(b => b.afterStepId === 'node-answer' || b.segmentId === 'tail:node-answer')
    .map(b => b.text)
    .join('')
}

/** Plan answer 正文 SSOT：优先 node-answer.result（与抽屉一致） */
export function resolvePlanAnswerText(
  msg: Pick<ChatMessage, 'content' | 'steps' | 'contentBlocks'>,
): string {
  if (!msg.steps?.some(s => s.phase === 'plan')) {
    return msg.content?.trim() ?? ''
  }
  const fromStep = msg.steps.find(s => s.id === 'node-answer')?.result?.trim()
  if (fromStep) return fromStep
  const fromBlocks = joinedPlanAnswerBlocks(msg.contentBlocks).trim()
  if (fromBlocks) return fromBlocks
  return msg.content?.trim() ?? ''
}

/** 折叠时间线：ReAct 展示最后一个 think 步骤之后的所有正文段（含紧邻分段），
 * 多轮会话折叠时不再只露最后一段终稿，中间穿插分析一并展示；
 * Plan 走 answer SSOT；无 think 或 think 后无正文时退化为仅最后一段 */
export function resolveCollapsedAnswerText(
  msg: Pick<ChatMessage, 'role' | 'content' | 'steps' | 'contentBlocks'>,
): string {
  if (msg.steps?.some(s => s.phase === 'plan')) {
    return resolvePlanAnswerText(msg).trim()
  }
  const steps = msg.steps ?? []
  const blocks = msg.contentBlocks
  if (blocks?.length) {
    let lastThinkIdx = -1
    for (let i = steps.length - 1; i >= 0; i--) {
      if (isThinkStepId(steps[i].id)) {
        lastThinkIdx = i
        break
      }
    }
    if (lastThinkIdx >= 0) {
      const visibleIds = new Set(steps.map(s => s.id))
      const chunks: string[] = []
      for (const block of blocks) {
        const text = block.text?.trim()
        if (!text) continue
        const anchor = resolveVisibleContentAnchor(block.afterStepId, steps, visibleIds)
        if (!anchor) continue
        const anchorIdx = steps.findIndex(s => s.id === anchor)
        if (anchorIdx < lastThinkIdx) continue
        chunks.push(block.text)
      }
      if (chunks.length) return chunks.join('\n\n').trim()
    }
    for (let i = blocks.length - 1; i >= 0; i--) {
      const last = blocks[i]?.text?.trim() ?? ''
      if (last) return last
    }
  }
  const content = msg.content?.trim() ?? ''
  if (content && !isPlanDrawerLeakContent(msg)) return content
  return content
}

/** node-answer.result 落步后，同步主时间线 contentBlocks / message.content（plan + 静态 workflow 共用） */
export function syncPlanAnswerContentFromStep(
  msg: Pick<ChatMessage, 'content' | 'steps' | 'contentBlocks'>,
): void {
  const fromStep = msg.steps?.find(s => s.id === 'node-answer')?.result
  if (fromStep == null || fromStep === '') return
  msg.content = fromStep
  msg.contentBlocks = [{
    segmentId: 'tail:node-answer',
    afterStepId: 'node-answer',
    text: fromStep,
  }]
}

/** 刷新 / 加载：Plan 消息统一剔除误入正文的 node 摘要（不依赖 node-answer 是否已存在） */
export function sanitizePlanAssistantMessage(
  msg: Pick<ChatMessage, 'role' | 'content' | 'steps' | 'contentBlocks'>,
): void {
  if (msg.role !== 'assistant' || !msg.steps?.length) return
  if (!isPlanWorkflowSteps(msg.steps)) return
  stripPlanDrawerLeakFromMessage(msg)
}

/** Plan 业务 node 摘要是否不应出现在主时间线正文 */
export function isPlanNodeLeakText(text: string, steps: ProcessingStep[]): boolean {
  const content = text.trim()
  if (!content || !isPlanWorkflowSteps(steps)) return false
  for (const step of steps) {
    if (!step.id.startsWith('node-') || step.id === 'node-answer') continue
    const label = formatStepLabel(step)
    const active = step.summary?.active?.trim()
    const after = step.summary?.after?.trim()
    const detail = step.detail?.trim()
    if (active && (content === active || content === `${label} ${active}` || content.includes(active))) return true
    if (after && (content === after || content === `${label} ${after}`)) return true
    if (detail && content === detail) return true
  }
  return false
}
/** Plan 业务 node 摘要/HITL 文案误入 message.content（非 answer 正文） */
export function isPlanDrawerLeakContent(msg: Pick<ChatMessage, 'content' | 'steps'>): boolean {
  const content = msg.content?.trim()
  if (!content || !msg.steps?.length || !isPlanWorkflowSteps(msg.steps)) return false
  if (isPlanNodeLeakText(content, msg.steps)) return true
  const answerText = msg.steps.find(s => s.id === 'node-answer')?.result?.trim()
  if (answerText && content === answerText) return false
  for (const step of msg.steps) {
    if (!step.id.startsWith('node-') || step.id === 'node-answer') continue
    const result = step.result?.trim()
    if (result && content === result) return true
  }
  return false
}

/** 清除 Plan 抽屉级摘要误入的正文（保留 node-answer） */
export function stripPlanDrawerLeakFromMessage(
  msg: Pick<ChatMessage, 'role' | 'content' | 'steps' | 'contentBlocks'>,
): void {
  if (msg.role !== 'assistant') return
  stripNonAnswerPlanContentBlocks(msg)
  if (!msg.content?.trim() || !msg.steps?.length) return
  if (!isPlanWorkflowSteps(msg.steps)) return
  const answerText = msg.steps.find(s => s.id === 'node-answer')?.result?.trim() ?? ''
  if (isPlanDrawerLeakContent(msg)) {
    msg.content = answerText
    if (msg.contentBlocks?.length) {
      const kept = msg.contentBlocks.filter(b => shouldRenderPlanMainContentBlock(b, msg.steps!))
      msg.contentBlocks = kept.length ? kept : undefined
    }
    return
  }
  // 续跑后正文可能混入 label + summary.active 前缀
  let content = msg.content.trim()
  for (const step of msg.steps) {
    if (!step.id.startsWith('node-') || step.id === 'node-answer') continue
    const label = formatStepLabel(step)
    const active = step.summary?.active?.trim()
    if (!active?.includes('等待用户确认')) continue
    const leak = `${label} ${active}`
    if (content === leak || content.startsWith(leak)) {
      content = content.slice(leak.length).trimStart()
    } else if (content === active) {
      content = ''
    }
  }
  if (content !== msg.content.trim()) {
    msg.content = answerText || content
  }
}

/** Plan 消息落库/刷新：剔除误锚到业务 node 的正文块，避免主时间线透出抽屉内容 */
export function stripNonAnswerPlanContentBlocks(
  msg: Pick<ChatMessage, 'role' | 'content' | 'steps' | 'contentBlocks'>,
): void {
  if (msg.role !== 'assistant' || !msg.steps?.length) return
  if (!isPlanWorkflowSteps(msg.steps)) return
  if (!msg.contentBlocks?.length) return
  const kept = msg.contentBlocks.filter(b => shouldRenderPlanMainContentBlock(b, msg.steps!))
  if (kept.length === msg.contentBlocks.length) return
  msg.contentBlocks = kept.length ? kept : undefined
  const answerIdx = msg.steps.findIndex(s => s.id === 'node-answer')
  const answerText = answerIdx >= 0 ? msg.steps[answerIdx].result?.trim() : ''
  const joined = joinedPlanAnswerBlocks(kept) || answerText
  if (joined) msg.content = joined
}

/**
 * 刷新 / 缓存合并后修复 message.content 与 contentBlocks 不一致（避免 timeline + 底部全文重复）。
 */
export function normalizeRestoredInterleavedContent(msg: ChatMessage): void {
  if (msg.role !== 'assistant') return
  if (msg.steps?.some(s => s.phase === 'plan')) {
    syncPlanAnswerContentFromStep(msg)
  }
  if (!msg.contentBlocks?.length) return
  stripPlanDrawerLeakFromMessage(msg)
  const joinedRaw = msg.steps?.some(s => s.phase === 'plan')
    ? joinedPlanAnswerBlocks(msg.contentBlocks)
    : joinedContentBlocks(msg.contentBlocks)
  const joined = joinedRaw.trim()
  if (!joined) return
  const content = (msg.content ?? '').trim()
  if (content === joined) {
    msg.content = joined
    return
  }
  if (content === joined + joined) {
    msg.content = joined
    return
  }
  if (joined.length > 0 && content.endsWith(joined) && content.length > joined.length) {
    const head = content.slice(0, content.length - joined.length).trim()
    if (head === joined || head.endsWith(joined)) {
      msg.content = joined
      return
    }
  }
  if (content.startsWith(joined) && content.length > joined.length) {
    msg.content = joined
  }
}

/** 是否展示 assistant 底部 msg-md（timeline 已穿插全文时隐藏，避免重复） */
export function shouldShowAssistantBottomContent(
  msg: Pick<ChatMessage, 'role' | 'content' | 'steps' | 'contentBlocks'>,
): boolean {
  if (msg.role !== 'assistant') return false
  if (!msg.content?.trim()) return false
  if (isPlanDrawerLeakContent(msg)) return false
  if (isContentFullyInterleaved(msg)) return false
  return true
}

/** 正文是否已全部挂到 timeline（可隐藏底部重复 msg-md） */
export function isContentFullyInterleaved(msg: ChatMessage): boolean {
  if (isPlanDrawerLeakContent(msg)) return true
  if (!msg.steps?.length) return false
  if (isPlanWorkflowSteps(msg.steps)) {
    const answerText = resolvePlanAnswerText(msg).trim()
    if (!answerText) return !msg.content?.trim()
    const content = (msg.content ?? '').trim()
    if (!content) return true
    return content === answerText || content.includes(answerText)
  }
  if (!msg.contentBlocks?.length) return false
  const joined = joinedContentBlocks(msg.contentBlocks).trim()
  if (!joined) return false
  const content = (msg.content ?? '').trim()
  if (!content) return true
  if (joined === content) return true
  if (content === joined + joined) return true
  if (content.length >= joined.length && content.includes(joined)) return true
  return false
}

export function resolveStreamingContentText(msg: ChatMessage): string {
  if (msg.steps?.some(s => s.phase === 'plan')) {
    return resolvePlanAnswerText(msg)
  }
  const blocks = msg.contentBlocks
  if (blocks?.length) return blocks[blocks.length - 1].text
  return msg.content ?? ''
}

export type TimelineContentRow = {
  kind: 'content'
  key: string
  text: string
  streaming: boolean
}

/** Plan 主时间线仅穿插 answer 正文；业务 node 的 detail/result 只在抽屉展示 */
function isPlanWorkflowSteps(steps: ProcessingStep[]): boolean {
  return steps.some(s => s.phase === 'plan')
}

export function shouldRenderPlanMainContentBlock(
  block: ContentBlock,
  steps: ProcessingStep[],
): boolean {
  if (!isPlanWorkflowSteps(steps)) return true
  const anchor = block.afterStepId
  if (anchor === 'node-answer' || block.segmentId === 'tail:node-answer') return true
  if (anchor.startsWith('node-') && anchor !== 'node-answer') return false
  const lastId = resolveContentAnchorStepId(steps)
  return lastId === 'node-answer' && (anchor === lastId || block.segmentId === `tail:${lastId}`)
}

export function contentRowsAfterStep(
  stepId: string,
  steps: ProcessingStep[],
  visibleStepIds: ReadonlySet<string>,
  blocks: ContentBlock[] | undefined,
  opts: { live: boolean; lastBlockIndex: number },
): TimelineContentRow[] {
  if (!blocks?.length) return []
  const planAnswerText = isPlanWorkflowSteps(steps)
    ? steps.find(s => s.id === 'node-answer')?.result?.trim()
    : ''
  const rows: TimelineContentRow[] = []
  blocks.forEach((block, idx) => {
    if (!block.text && !planAnswerText) return
    if (!shouldRenderPlanMainContentBlock(block, steps)) return
    const text = (planAnswerText && (block.afterStepId === 'node-answer' || block.segmentId === 'tail:node-answer'))
      ? planAnswerText
      : block.text
    if (!text) return
    if (isPlanNodeLeakText(text, steps)) return
    const displayAnchor = resolveVisibleContentAnchor(block.afterStepId, steps, visibleStepIds)
    if (displayAnchor !== stepId) return
    rows.push({
      kind: 'content',
      key: `content-${block.segmentId}-${stepId}`,
      text,
      streaming: opts.live && idx === opts.lastBlockIndex,
    })
  })
  return rows
}

export function leadingContentRows(
  _steps: ProcessingStep[],
  _visibleStepIds: ReadonlySet<string>,
  blocks: ContentBlock[] | undefined,
  opts: { live: boolean; lastBlockIndex: number },
): TimelineContentRow[] {
  return []
}

export function resolveLastContentBlockIndex(blocks: ContentBlock[] | undefined): number {
  if (!blocks?.length) return -1
  return blocks.length - 1
}

/**
 * 历史消息 hydrate：Plan answer 正文仅存 message.content + steps，
 * 刷新后重建 contentBlocks 并修复 node-answer.result（后端 result delta 历史 bug）。
 */
export function hydratePlanAnswerFromContent(
  msg: Pick<ChatMessage, 'role' | 'content' | 'steps' | 'contentBlocks'>,
): void {
  if (msg.role !== 'assistant') return
  if (!msg.steps?.length) return
  if (!msg.steps.some(s => s.phase === 'plan')) return
  sanitizePlanAssistantMessage(msg)
  const answerIdx = msg.steps.findIndex(s => s.id === 'node-answer')
  if (answerIdx < 0) return

  const answerStep = msg.steps[answerIdx]
  const fromStep = answerStep.result?.trim()
  const fromContent = msg.content?.trim() ?? ''
  const canonical = fromStep || fromContent
  if (!canonical) return
  if (!fromStep && fromContent) {
    msg.steps[answerIdx] = { ...answerStep, result: fromContent }
  }
  syncPlanAnswerContentFromStep(msg)
  stripPlanDrawerLeakFromMessage(msg)
}

/** ReAct 步完成后的执行空档提示：最后可见步已终态（think/tool/tasks 等）、其后方无正文/新步骤跟进时展示。
 * 覆盖模型长时间生成 tool 参数（如写大文件）或下一次推理的空档，防止用户误以为卡死；
 * 运行中步骤自带 pulse 不重复提示，HITL 等待由确认框承载。新步骤出现后自然消失 */
/** 执行空档占位：正在处理、最后可见步已终态（done/error/skipped）时展示三点，
 * 覆盖模型长时间生成 tool 参数（如写大文件）或下一次推理的空档，防止用户误以为卡死。
 * 运行中步骤自带 pulse 不重复提示，HITL 等待由确认框承载；新步骤出现或消息终态后自然消失。
 * 该步之后是否有（含已输出的历史）正文不抑制占位——占位表示「还有内容在生成」，与正文展示并存 */
export function shouldShowAfterThinkPendingHint(opts: {
  processing: boolean
  lastStep?: ProcessingStep
}): boolean {
  if (!opts.processing) return false
  const step = opts.lastStep
  if (!step) return false
  // 仅已完成步骤后的空档展示；running/pending（含 HITL awaiting）不提示
  if (step.lifecycle !== 'done' && step.lifecycle !== 'error' && step.lifecycle !== 'skipped') return false
  return true
}

/** 时间线末行为工具组（连续同类工具折叠成行）时的占位判定：
 * 组内仍有运行中步自带 pulse 不提示；全终态组取组内最后一步参与空档判定 */
export type PendingHintLastRow =
  | { kind: 'step'; step: ProcessingStep }
  | { kind: 'toolGroup'; steps: ProcessingStep[]; anyRunning: boolean }

export function shouldShowPendingHintForLastRow(opts: {
  processing: boolean
  lastRow?: PendingHintLastRow
}): boolean {
  const row = opts.lastRow
  if (!row) return false
  if (row.kind === 'toolGroup') {
    if (row.anyRunning) return false
    return shouldShowAfterThinkPendingHint({
      processing: opts.processing,
      lastStep: row.steps[row.steps.length - 1],
    })
  }
  return shouldShowAfterThinkPendingHint({
    processing: opts.processing,
    lastStep: row.step,
  })
}

/** 无法映射到可见步骤的正文块 */
export function orphanContentRows(
  steps: ProcessingStep[],
  visibleStepIds: ReadonlySet<string>,
  blocks: ContentBlock[] | undefined,
  opts: { live: boolean; lastBlockIndex: number },
): TimelineContentRow[] {
  if (!blocks?.length) return []
  const planAnswerText = isPlanWorkflowSteps(steps)
    ? steps.find(s => s.id === 'node-answer')?.result?.trim()
    : ''
  const rows: TimelineContentRow[] = []
  blocks.forEach((block, idx) => {
    if (!block.text && !planAnswerText) return
    if (!shouldRenderPlanMainContentBlock(block, steps)) return
    const text = (planAnswerText && (block.afterStepId === 'node-answer' || block.segmentId === 'tail:node-answer'))
      ? planAnswerText
      : block.text
    if (!text) return
    if (isPlanNodeLeakText(text, steps)) return
    const displayAnchor = resolveVisibleContentAnchor(block.afterStepId, steps, visibleStepIds)
    if (displayAnchor !== null) return
    rows.push({
      kind: 'content',
      key: `content-${block.segmentId}-orphan`,
      text,
      streaming: opts.live && idx === opts.lastBlockIndex,
    })
  })
  return rows
}
