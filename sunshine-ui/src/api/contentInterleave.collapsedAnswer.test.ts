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
        {
          id: 'plan',
          phase: 'plan',
          lifecycle: 'done',
          metadata: {
            planApproval: {
              planGraph: {
                nodes: [{ id: 'n1', type: 'llm', displayName: 'A' }],
                edges: [],
              },
            },
          },
        },
        { id: 'node-answer', phase: 'node', lifecycle: 'done', result: '计划终稿' },
      ],
      contentBlocks: [{ segmentId: 'tail:node-answer', afterStepId: 'node-answer', text: '块' }],
    }))).toBe('计划终稿')
  })

  it('ReAct：展示最后一个 think 之后的所有正文段', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '全文',
      steps: [
        { id: 'think', phase: 'think', lifecycle: 'done' },
        { id: 'tool__exec_1', phase: 'tool', lifecycle: 'done' },
        { id: 'think-2', phase: 'think', lifecycle: 'done' },
        { id: 'tool__exec_2', phase: 'tool', lifecycle: 'done' },
      ],
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '第一轮分析' },
        { segmentId: 'content-2', afterStepId: 'tool__exec_1', text: '工具结果说明' },
        { segmentId: 'content-3', afterStepId: 'think-2', text: '第二轮结论' },
        { segmentId: 'content-4', afterStepId: 'tool__exec_2', text: '最终输出' },
      ],
    }))).toBe('第二轮结论\n\n最终输出')
  })

  it('ReAct：正文锚定在最后一个 think 之前时被排除', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '',
      steps: [
        { id: 'think', phase: 'think', lifecycle: 'done' },
        { id: 'tool__exec_1', phase: 'tool', lifecycle: 'done' },
        { id: 'think-2', phase: 'think', lifecycle: 'done' },
      ],
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '早期分析' },
        { segmentId: 'content-2', afterStepId: 'tool__exec_1', text: '中间过程' },
        { segmentId: 'content-3', afterStepId: 'think-2', text: '收尾' },
      ],
    }))).toBe('收尾')
  })

  it('ReAct：无 think 步骤时退化为仅最后一段', () => {
    expect(resolveCollapsedAnswerText(msg({
      content: '',
      steps: [
        { id: 'tool__exec_1', phase: 'tool', lifecycle: 'done' },
        { id: 'tool__exec_2', phase: 'tool', lifecycle: 'done' },
      ],
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'tool__exec_1', text: '前段' },
        { segmentId: 'content-2', afterStepId: 'tool__exec_2', text: '尾段' },
      ],
    }))).toBe('尾段')
  })
})
