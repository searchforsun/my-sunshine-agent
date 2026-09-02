// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage } from './chat'
import type { SessionState } from './chatSessionRegistry'
import { consumeChatSseStream } from './chatSessionSseConsumer'

function makeSession(): SessionState {
  const msg: ChatMessage = {
    id: 'a1',
    role: 'assistant',
    content: '',
    status: 'streaming',
    steps: [],
  }
  return {
    id: 'c1',
    messages: [msg],
    loading: true,
    streamRevision: 0,
    containerEl: document.createElement('div'),
    mounted: true,
    abort: null,
    requestId: 0,
  } as SessionState
}

/** 分批推送 N 个 chunk SSE 事件后关闭（每批一个事件，循环末尾的让出等待会逐批触发） */
function streamOfChunks(chunks: string[], seqStart = 1): Response {
  const encoder = new TextEncoder()
  const events = chunks.map((text, i) => `id: ${seqStart + i}\ndata: ${JSON.stringify({ type: 'chunk', text })}\n\n`)
  let i = 0
  return {
    body: new ReadableStream<Uint8Array>({
      pull(controller) {
        if (i < events.length) {
          controller.enqueue(encoder.encode(events[i]))
          i++
        } else {
          controller.close()
        }
      },
    }),
  } as unknown as Response
}

function setVisibility(v: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => v })
}

function zeroDelayTimeoutCalls(): number {
  const calls = vi.mocked(setTimeout).mock.calls
  return calls.filter(c => c[1] === 0).length
}

describe('consumeChatSseStream · 后台 tab 不因 timer 节流卡住消费', () => {
  beforeEach(() => {
    vi.spyOn(globalThis, 'setTimeout')
  })
  afterEach(() => {
    vi.restoreAllMocks()
    const desc = Object.getOwnPropertyDescriptor(document, 'visibilityState')
    if (desc?.get && (desc.get as () => string)() !== 'visible') {
      setVisibility('visible')
    }
  })

  it('页面隐藏时跳过循环末尾 setTimeout(0) 让出（避免后台 timer 节流→网络积压）', async () => {
    setVisibility('hidden')
    const s = makeSession()
    await consumeChatSseStream(s, streamOfChunks(['你好', '，世界', '！']), {}, {})
    // hidden：不因让出等待被 Chrome 节流到 1s/批
    expect(zeroDelayTimeoutCalls()).toBe(0)
    expect(s.messages[s.messages.length - 1].content).toBe('你好，世界！')
  })

  it('页面可见时保留 setTimeout(0) 让出（渲染节奏不受影响）', async () => {
    setVisibility('visible')
    const s = makeSession()
    await consumeChatSseStream(s, streamOfChunks(['你好', '，世界', '！']), {}, {})
    expect(zeroDelayTimeoutCalls()).toBeGreaterThan(0)
    expect(s.messages[s.messages.length - 1].content).toBe('你好，世界！')
  })
})
