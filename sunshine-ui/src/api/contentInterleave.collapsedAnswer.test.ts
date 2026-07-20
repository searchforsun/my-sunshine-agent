import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import { resolveCollapsedAnswerText } from './contentInterleave'

function msg(partial: Partial<ChatMessage>): ChatMessage {
  return { role: 'assistant', content: '', ...partial }
}

describe('resolveCollapsedAnswerText', () => {
  it('prefers last contentBlock over full message.content', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '中间段尾段',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '中间段' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: '## 终稿标题\n要点' },
      ],
    }))).toBe('## 终稿标题\n要点')
  })

  it('uses last non-empty block when earlier blocks exist', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '过程说明' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: '最终正文' },
      ],
    }))).toBe('最终正文')
  })

  it('falls back to message.content when no blocks', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: 'only content',
      contentBlocks: undefined,
    }))).toBe('only content')
  })

  it('skips trailing empty blocks', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: 'fallback',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '终稿' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: '   ' },
      ],
    }))).toBe('终稿')
  })

  it('uses plan answer SSOT for plan workflows', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '误入',
      steps: [
        { id: 'plan', phase: 'plan', lifecycle: 'done' },
        { id: 'node-answer', phase: 'node', lifecycle: 'done', result: '计划终稿' },
      ],
      contentBlocks: [{ segmentId: 'tail:node-answer', afterStepId: 'node-answer', text: '块' }],
    }))).toBe('计划终稿')
  })
})
