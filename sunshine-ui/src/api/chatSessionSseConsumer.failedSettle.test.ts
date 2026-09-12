// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import type { ChatMessage } from './chat'
import type { SessionState } from './chatSessionRegistry'
import { consumeChatSseStream } from './chatSessionSseConsumer'

/**
 * 回归：消息级 failed 兜底收口。后端失败路径（如网关静默超时→上游 500）只保证
 * metaError + status=failed 元事件，running 工具步的 paused 快照若在途丢失，
 * 前端须在 failed 分支统一收口，避免工具卡永续「执行中」走表。
 */
describe('consumeChatSseStream · failed 终态兜底收口 running 步骤', () => {
  it('收到 status=failed 后，残余 running 工具步收为 done 且停止 live 计时', async () => {
    const msg: ChatMessage = {
      id: 'a1',
      role: 'assistant',
      content: '',
      status: 'streaming',
      steps: [],
    }
    const s = {
      id: 'c1',
      messages: [msg],
      loading: true,
      streamRevision: 0,
      containerEl: document.createElement('div'),
      mounted: true,
      abort: null,
      requestId: 0,
    } as unknown as SessionState

    const runningStep = {
      id: 'tool-sandbox__exec@1725500000000',
      phase: 'tool',
      lifecycle: 'running',
      summary: { active: '正在执行命令' },
      startedAt: Date.now() - 5_000,
    }
    const encoder = new TextEncoder()
    const events = [
      `data: ${JSON.stringify({ type: 'step', ...runningStep })}\n\n`,
      `data: ${JSON.stringify({ type: 'error', text: '500 Internal Server Error' })}\n\n`,
      `data: ${JSON.stringify({ type: 'message', id: 'a1', status: 'failed' })}\n\n`,
    ]
    const response = {
      body: new ReadableStream<Uint8Array>({
        pull(controller) {
          if (events.length) {
            controller.enqueue(encoder.encode(events.shift()))
          } else {
            controller.close()
          }
        },
      }),
    } as unknown as Response

    await consumeChatSseStream(s, response, {}, {})

    const last = s.messages[s.messages.length - 1]
    expect(last.status).toBe('failed')
    expect(last.steps).toHaveLength(1)
    expect(last.steps![0].lifecycle).toBe('done')
    expect(last.steps![0].endedAt).toBeTruthy()
    expect(last.steps![0].clientStartedAt).toBeUndefined()
  })
})
