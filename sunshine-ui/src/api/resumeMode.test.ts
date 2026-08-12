import { describe, expect, it } from 'vitest'
import {
  isExecutionRestartMessage,
  isReactAssistantMessage,
  resolveResumeMode,
  resumeButtonLabel,
} from './resumeMode'
import type { ChatMessage } from './chat'

function reactMsg(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'a1',
    role: 'assistant',
    content: '',
    status: 'interrupted',
    intent: 'react',
    steps: [
      { id: 'intent', phase: 'intent', lifecycle: 'done' },
      { id: 'skill', phase: 'skill', lifecycle: 'done' },
      { id: 'tasks', phase: 'tasks', lifecycle: 'paused' },
    ],
    ...overrides,
  } as ChatMessage
}

describe('resumeMode · ReAct 无感续跑', () => {
  it('ReAct interrupted → checkpoint（接着进度）且按钮仍为继续生成', () => {
    const msg = reactMsg()
    expect(isReactAssistantMessage(msg)).toBe(true)
    expect(resolveResumeMode(msg)).toBe('checkpoint')
    expect(resumeButtonLabel(msg)).toBe('继续生成')
    expect(isExecutionRestartMessage(msg)).toBe(false)
  })

  it('Plan/Workflow 节点 paused → 继续执行', () => {
    const msg = reactMsg({
      intent: 'plan-workflow',
      steps: [{ id: 'node-1', phase: 'node', lifecycle: 'paused' }],
    })
    expect(isReactAssistantMessage(msg)).toBe(false)
    expect(resolveResumeMode(msg)).toBe('checkpoint')
    expect(resumeButtonLabel(msg)).toBe('继续执行')
  })
})
