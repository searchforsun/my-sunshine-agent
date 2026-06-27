/**
 * ReAct 正文与 timeline 步骤穿插：按 SSE 到达时锚定到最后一步，渲染时插入对应步骤之后。
 */
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'
import { findRunningStepId } from './processingSteps'

function mergeStreamChunk(existing: string, chunk: string): string {
  const maxOverlap = Math.min(existing.length, chunk.length, 64)
  for (let n = maxOverlap; n > 0; n--) {
    if (existing.endsWith(chunk.slice(0, n))) return existing + chunk.slice(n)
  }
  return existing + chunk
}

export interface ContentBlock {
  /** 正文锚定在其后的步骤 id；null 表示步骤列表之前 */
  afterStepId: string | null
  text: string
}

/** ReAct 主 timeline 不展示、但 SSE 仍可能锚定的步骤 */
export function isHiddenReactTimelineStep(step: ProcessingStep): boolean {
  return step.id === 'generate' || step.phase === 'generate'
}

/** 隐藏步的正文块改挂到 timeline 中前一个可见步骤之后 */
export function resolveVisibleContentAnchor(
  afterStepId: string | null,
  steps: ProcessingStep[],
  visibleStepIds: ReadonlySet<string>,
): string | null {
  if (afterStepId === null) return null
  if (visibleStepIds.has(afterStepId)) return afterStepId
  const idx = steps.findIndex(s => s.id === afterStepId)
  if (idx < 0) return afterStepId
  for (let i = idx - 1; i >= 0; i--) {
    if (visibleStepIds.has(steps[i].id)) return steps[i].id
  }
  return null
}

/** 正文 chunk 锚定：优先 running 步，否则 timeline 最后一步 */
export function resolveContentAnchorStepId(steps: ProcessingStep[]): string | null {
  if (!steps.length) return null
  const running = findRunningStepId(steps)
  if (running) return running
  return steps[steps.length - 1].id
}

export function appendInterleavedContent(
  msg: ChatMessage,
  chunk: string,
  resume: boolean,
): void {
  if (!chunk) return
  msg.content = resume
    ? mergeStreamChunk(msg.content ?? '', chunk)
    : (msg.content ?? '') + chunk
  const steps = msg.steps
  if (!steps?.length) return
  if (!msg.contentBlocks) msg.contentBlocks = []
  const anchor = resolveContentAnchorStepId(steps)
  const blocks = msg.contentBlocks
  const last = blocks[blocks.length - 1]
  if (last && last.afterStepId === anchor) {
    last.text = resume ? mergeStreamChunk(last.text, chunk) : last.text + chunk
  } else {
    blocks.push({ afterStepId: anchor, text: chunk })
  }
}

/** 正文是否已全部挂到 timeline（可隐藏底部重复 msg-md） */
export function isContentFullyInterleaved(msg: ChatMessage): boolean {
  if (!msg.contentBlocks?.length || !msg.steps?.length) return false
  const joined = msg.contentBlocks.map(b => b.text).join('')
  return joined === (msg.content ?? '')
}

export function resolveStreamingContentText(msg: ChatMessage): string {
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

/** 某可见步骤之后、按 SSE 顺序排列的正文块（含锚定在隐藏步如 generate 上的块） */
export function contentRowsAfterStep(
  stepId: string,
  steps: ProcessingStep[],
  visibleStepIds: ReadonlySet<string>,
  blocks: ContentBlock[] | undefined,
  opts: { live: boolean; lastBlockIndex: number },
): TimelineContentRow[] {
  if (!blocks?.length) return []
  const rows: TimelineContentRow[] = []
  blocks.forEach((block, idx) => {
    if (!block.text) return
    const displayAnchor = resolveVisibleContentAnchor(block.afterStepId, steps, visibleStepIds)
    if (displayAnchor !== stepId) return
    rows.push({
      kind: 'content',
      key: `content-${idx}-${stepId}`,
      text: block.text,
      streaming: opts.live && idx === opts.lastBlockIndex,
    })
  })
  return rows
}

/** 步骤列表之前的正文块（afterStepId === null） */
export function leadingContentRows(
  _steps: ProcessingStep[],
  _visibleStepIds: ReadonlySet<string>,
  blocks: ContentBlock[] | undefined,
  opts: { live: boolean; lastBlockIndex: number },
): TimelineContentRow[] {
  if (!blocks?.length) return []
  const rows: TimelineContentRow[] = []
  blocks.forEach((block, idx) => {
    if (!block.text || block.afterStepId !== null) return
    rows.push({
      kind: 'content',
      key: `content-${idx}-start`,
      text: block.text,
      streaming: opts.live && idx === opts.lastBlockIndex,
    })
  })
  return rows
}

export function resolveLastContentBlockIndex(blocks: ContentBlock[] | undefined): number {
  if (!blocks?.length) return -1
  return blocks.length - 1
}

/** 无法映射到可见步骤的正文块（Plan 过滤 tool 步等；已在 afterStep 展示的块不得重复） */
export function orphanContentRows(
  steps: ProcessingStep[],
  visibleStepIds: ReadonlySet<string>,
  blocks: ContentBlock[] | undefined,
  opts: { live: boolean; lastBlockIndex: number },
): TimelineContentRow[] {
  if (!blocks?.length) return []
  const rows: TimelineContentRow[] = []
  blocks.forEach((block, idx) => {
    if (!block.text || block.afterStepId === null) return
    const displayAnchor = resolveVisibleContentAnchor(block.afterStepId, steps, visibleStepIds)
    if (displayAnchor !== null) return
    rows.push({
      kind: 'content',
      key: `content-${idx}-orphan`,
      text: block.text,
      streaming: opts.live && idx === opts.lastBlockIndex,
    })
  })
  return rows
}
