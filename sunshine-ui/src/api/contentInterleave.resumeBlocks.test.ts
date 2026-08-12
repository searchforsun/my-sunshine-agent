import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'
import {
  beginContentSegment,
  appendSegmentContent,
  endContentSegment,
  pruneContentBlocksForReactResume,
  resolveSegmentId,
  clearSegmentIdRemap,
} from './contentInterleave'

function think(id: string, lifecycle: ProcessingStep['lifecycle'] = 'done'): ProcessingStep {
  return { id, phase: 'think', lifecycle }
}

function tool(id: string): ProcessingStep {
  return { id, phase: 'tool', lifecycle: 'done' }
}

describe('pruneContentBlocksForReactResume', () => {
  it('保留锚在最后一个完整 think（其后有 tool）及之前的正文', () => {
    const steps = [
      think('think'),
      tool('tool-a'),
      think('think-2'),
      tool('tool-b'),
      think('think-3', 'paused'),
    ]
    const blocks = [
      { segmentId: 'content-1', afterStepId: 'think', text: '一段' },
      { segmentId: 'content-2', afterStepId: 'think-2', text: '二段' },
      { segmentId: 'content-3', afterStepId: 'think-3', text: '半截' },
    ]
    expect(pruneContentBlocksForReactResume(blocks, steps)).toEqual([
      { segmentId: 'content-1', afterStepId: 'think', text: '一段' },
      { segmentId: 'content-2', afterStepId: 'think-2', text: '二段' },
    ])
  })

  it('锚点步骤已不在 steps 中的正文丢弃', () => {
    const steps = [think('think'), tool('tool-a')]
    const blocks = [
      { segmentId: 'content-1', afterStepId: 'think', text: '保留' },
      { segmentId: 'content-9', afterStepId: 'think-gone', text: '丢弃' },
    ]
    expect(pruneContentBlocksForReactResume(blocks, steps)).toEqual([
      { segmentId: 'content-1', afterStepId: 'think', text: '保留' },
    ])
  })
})

describe('beginContentSegment · 一锚点一段', () => {
  it('同 segmentId 不同 afterStepId 时重映射，避免新正文灌进旧锚点', () => {
    const msg: ChatMessage = {
      id: 'a1',
      role: 'assistant',
      content: '旧段',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '旧段' },
      ],
    }
    beginContentSegment(msg, 'content-1', 'think-2')
    expect(msg.contentBlocks).toHaveLength(2)
    expect(msg.contentBlocks![0]).toEqual({
      segmentId: 'content-1', afterStepId: 'think', text: '旧段',
    })
    const remapped = resolveSegmentId(msg, 'content-1')
    expect(remapped).not.toBe('content-1')
    expect(msg.contentBlocks!.some(b => b.segmentId === remapped && b.afterStepId === 'think-2')).toBe(true)

    appendSegmentContent(msg, 'content-1', '新段正文', true)
    expect(msg.contentBlocks![0].text).toBe('旧段')
    const newBlock = msg.contentBlocks!.find(b => b.segmentId === remapped)
    expect(newBlock?.text).toBe('新段正文')
    clearSegmentIdRemap(msg.id)
  })

  it('同 afterStepId 新 segment 占领锚点并清空旧文（续跑不双段）', () => {
    const msg: ChatMessage = {
      id: 'a2',
      role: 'assistant',
      content: '沙箱无 Node.js 且无权限安装。检查是否有其他 JS 运行时：',
      contentBlocks: [
        {
          segmentId: 'content-3',
          afterStepId: 'think-2',
          text: '沙箱无 Node.js 且无权限安装。检查是否有其他 JS 运行时：',
        },
      ],
    }
    beginContentSegment(msg, 'content-4', 'think-2')
    expect(msg.contentBlocks).toEqual([
      { segmentId: 'content-4', afterStepId: 'think-2', text: '' },
    ])
    expect(msg.content).toBe('')
    appendSegmentContent(msg, 'content-4', '改用 bun 运行：', false)
    expect(msg.contentBlocks).toEqual([
      { segmentId: 'content-4', afterStepId: 'think-2', text: '改用 bun 运行：' },
    ])
    expect(msg.content).toBe('改用 bun 运行：')
  })

  it('content_end 去掉与更早段精确重复的误放正文', () => {
    clearSegmentIdRemap('a1')
    const msg: ChatMessage = {
      id: 'a1',
      role: 'assistant',
      content: '构建成功。验证合并产物质量：',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '构建成功。验证合并产物质量：' },
      ],
    }
    beginContentSegment(msg, 'content-1', 'think-2')
    appendSegmentContent(msg, 'content-1', '构建成功。验证合并产物质量：')
    endContentSegment(msg, 'content-1')
    expect(msg.contentBlocks).toEqual([
      { segmentId: 'content-1', afterStepId: 'think', text: '构建成功。验证合并产物质量：' },
    ])
    clearSegmentIdRemap(msg.id)
  })
})
