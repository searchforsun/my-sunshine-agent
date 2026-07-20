import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import { resolveCollapsedAnswerText } from './contentInterleave'

function msg(partial: Partial<ChatMessage>): ChatMessage {
  return { role: 'assistant', content: '', ...partial }
}

describe('resolveCollapsedAnswerText', () => {
  it('prefers message.content when not plan leak', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '最终回答',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '中间段' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: '尾段' },
      ],
    }))).toBe('最终回答')
  })

  it('falls back to joined contentBlocks', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: 'A' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: 'B' },
      ],
    }))).toBe('AB')
  })

  it('falls back to last block when join empty', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '   ',
      contentBlocks: [{ segmentId: 'content-1', afterStepId: 'think', text: 'only' }],
    }))).toBe('only')
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
