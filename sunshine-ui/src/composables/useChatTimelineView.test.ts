// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import type { ChatMessage } from '../api/chat'
import type { ProcessingStep } from '../api/processingSteps'
import { useChatTimelineView } from './useChatTimelineView'

function makeMsg(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: `msg-${Math.random().toString(36).slice(2)}`,
    role: 'assistant',
    content: '',
    status: 'completed',
    createdAt: Date.now(),
    ...overrides,
  } as ChatMessage
}

function makeStep(id: string, phase: ProcessingStep['phase']): ProcessingStep {
  return { id, phase, lifecycle: 'done', ts: 1 } as ProcessingStep
}

describe('useChatTimelineView 历史消息时间线缓存', () => {
  it('依赖引用未变时复用缓存结果（返回同一引用，不新建数组）', () => {
    const messages = ref<ChatMessage[]>([])
    const view = useChatTimelineView(messages, ref(false))

    const step = makeStep('think', 'think')
    const msg = makeMsg({ steps: [step] })
    messages.value = [msg]

    const first = view.resolveTimelineContext(msg)
    const second = view.resolveTimelineContext(msg)

    // 同一次渲染内重复调用返回同一引用
    expect(second.steps).toBe(first.steps)
  })

  it('steps 引用变化时重新计算', () => {
    const messages = ref<ChatMessage[]>([])
    const view = useChatTimelineView(messages, ref(false))

    const msg = makeMsg({ steps: [makeStep('think', 'think')] })
    messages.value = [msg]

    const first = view.resolveTimelineContext(msg)
    // 模拟流式更新：steps 换新数组
    msg.steps = [makeStep('think', 'think'), makeStep('tool__exec_1', 'tool')]
    const second = view.resolveTimelineContext(msg)

    expect(second.steps).not.toBe(first.steps)
    expect(second.steps).toHaveLength(2)
  })

  it('resolveUserQuery 缓存按消息 id 命中，结果稳定', () => {
    const user = makeMsg({ id: 'user-1', role: 'user', content: '帮我分析待办' })
    const assistant = makeMsg({ id: 'asst-1', role: 'assistant', content: '好的' })
    const messages = ref<ChatMessage[]>([user, assistant])
    const view = useChatTimelineView(messages, ref(false))

    const q1 = view.resolveUserQuery(1)
    const q2 = view.resolveUserQuery(1)
    expect(q1).toBe('帮我分析待办')
    expect(q2).toBe(q1)
  })
})

describe('useChatTimelineView showStreamWaiting', () => {
  it('已有时间线步骤时不挂底部三点（避免与 OperationStack 空档三点叠两行）', async () => {
    const messages = ref<ChatMessage[]>([
      makeMsg({
        status: 'streaming',
        content: '',
        steps: [makeStep('think', 'think')],
      }),
    ])
    const loading = ref(true)
    const view = useChatTimelineView(messages, loading)
    await new Promise(r => setTimeout(r, 2100))
    expect(view.showStreamWaiting.value).toBe(false)
  })

  it('尚无 steps/正文时，静默满 2s 后显示底部三点', async () => {
    const messages = ref<ChatMessage[]>([
      makeMsg({ status: 'streaming', content: '', steps: [] }),
    ])
    const loading = ref(true)
    const view = useChatTimelineView(messages, loading)
    expect(view.showStreamWaiting.value).toBe(false)
    await new Promise(r => setTimeout(r, 2100))
    expect(view.showStreamWaiting.value).toBe(true)
  })
})
