import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessage } from './chat'
import type { ProcessingStep } from './processingSteps'
import type { SessionState } from './chatSessionRegistry'
import {
  bumpAssistantMessage,
  flushAssistantMessageBump,
  scheduleAssistantMessageBump,
} from './chatSessionMutations'

function sessionWithSteps(steps: ProcessingStep[]): SessionState {
  const msg: ChatMessage = {
    id: 'a1',
    role: 'assistant',
    content: '',
    status: 'streaming',
    steps,
  }
  return {
    id: 'c1',
    messages: [msg],
    loading: true,
    streamRevision: 0,
    containerEl: null as unknown as HTMLElement,
    abortController: null,
  } as SessionState
}

describe('bumpAssistantMessage perf', () => {
  afterEach(() => {
    flushAssistantMessageBump()
    vi.useRealTimers()
  })

  it('does not deep-clone step.detail (keeps same string ref)', () => {
    const detail = 'x'.repeat(50_000)
    const step: ProcessingStep = {
      id: 'tool-sandbox__write@1',
      phase: 'tool',
      lifecycle: 'done',
      detail,
      summary: { after: '写文件' },
    }
    const s = sessionWithSteps([step])
    bumpAssistantMessage(s)
    const next = s.messages[0].steps?.[0]
    expect(next?.detail).toBe(detail)
    expect(s.streamRevision).toBe(1)
  })

  it('scheduleAssistantMessageBump throttles to one bump', () => {
    vi.useFakeTimers()
    const s = sessionWithSteps([])
    scheduleAssistantMessageBump(s)
    scheduleAssistantMessageBump(s)
    scheduleAssistantMessageBump(s)
    expect(s.streamRevision).toBe(0)
    vi.advanceTimersByTime(80)
    expect(s.streamRevision).toBe(1)
  })
})
