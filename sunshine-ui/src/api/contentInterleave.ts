/**
 * 正文穿插：
 * - ReAct：content_start → content(segmentId) → content_end
 * - Plan answer / legacy：plain content 自动锚定 timeline 末步，渲染时 node-* 回退到 plan
 */
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'

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

/** simple-llm 仍展示 generate 步骤行；ReAct 不再下发 generate */
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
  appendMessageContent(msg, chunk, resume)
  const block = findBlock(msg.contentBlocks, segmentId)
  if (!block) return
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
  // Plan answer 正文走 node-answer.result（step_delta），chunk 会破坏表格换行
  if (steps?.some(s => s.phase === 'plan')) return
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

/** node-answer.result 落步后，同步主时间线 contentBlocks / message.content */
export function syncPlanAnswerContentFromStep(
  msg: Pick<ChatMessage, 'content' | 'steps' | 'contentBlocks'>,
): void {
  if (!msg.steps?.some(s => s.phase === 'plan')) return
  const fromStep = msg.steps.find(s => s.id === 'node-answer')?.result?.trim()
  if (!fromStep) return
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
    const label = step.label?.trim() || step.id.slice('node-'.length)
    const active = step.summary?.active?.trim()
    const after = step.summary?.after?.trim()
    const detail = step.detail?.trim()
    if (active && (content === active || content === `${label} ${active}` || content.includes(active))) return true
    if (after && after !== '已暂停' && (content === after || content === `${label} ${after}`)) return true
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
    const label = step.label?.trim() || step.id.slice('node-'.length)
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
  const joined = msg.steps?.some(s => s.phase === 'plan')
    ? joinedPlanAnswerBlocks(msg.contentBlocks)
    : joinedContentBlocks(msg.contentBlocks)
  if (!joined) return
  const content = msg.content ?? ''
  if (content === joined) return
  if (content === joined + joined) {
    msg.content = joined
    return
  }
  if (joined.length > 0 && content.endsWith(joined) && content.length > joined.length) {
    const head = content.slice(0, content.length - joined.length)
    if (head === joined || head.endsWith(joined)) {
      msg.content = joined
      return
    }
  }
  if (content.startsWith(joined) && content.length > joined.length) {
    msg.content = joined
  }
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
  const joined = joinedContentBlocks(msg.contentBlocks)
  if (!joined.trim()) return false
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
