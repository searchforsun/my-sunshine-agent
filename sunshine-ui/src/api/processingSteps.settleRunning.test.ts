import { describe, it, expect } from 'vitest'
import { settleRunningSteps, type ProcessingStep } from './processingSteps'

/**
 * 回归：消息级 completed 兜底收口。后端 done 快照在途丢失时，残余 running 工具步
 * 会永续 live 计时（liveElapsedMs = now - clientStartedAt），表现为已完成消息的耗时仍实时增长。
 * settleRunningSteps 以消息级终态为权威信号，统一把残余 running 收为 done。
 */
describe('settleRunningSteps 消息级终态收口', () => {
  it('running 工具步统一收为 done，清 clientStartedAt，以 settledAt 作为 endedAt', () => {
    const steps: ProcessingStep[] = [
      {
        id: 'tool-rag-1', phase: 'tool', lifecycle: 'running',
        summary: { active: '正在搜索知识库' }, clientStartedAt: 1_000_000, startedAt: 999_000,
      },
      {
        id: 'tool-rag-2', phase: 'tool', lifecycle: 'running',
        summary: { active: '正在搜索内容' }, clientStartedAt: 1_000_050, startedAt: 999_050,
      },
    ]
    const settledAt = 1_213_400
    const out = settleRunningSteps(steps, settledAt)
    expect(out).toHaveLength(2)
    for (const s of out) {
      expect(s.lifecycle).toBe('done')
      expect(s.endedAt).toBe(settledAt)
      expect(s.clientStartedAt).toBeUndefined()
    }
    // 原数组不被原地修改（返回新数组，触发 Vue 重渲染）
    expect(steps[0].lifecycle).toBe('running')
  })

  it('已 done / 已在其它终态的步保持原值不被改写', () => {
    const steps: ProcessingStep[] = [
      { id: 'tool-done', phase: 'tool', lifecycle: 'done', startedAt: 1000, endedAt: 2000, durationMs: 1000 },
      { id: 'tool-paused', phase: 'tool', lifecycle: 'paused', startedAt: 1000, endedAt: 1500 },
      { id: 'think-done', phase: 'think', lifecycle: 'done' },
    ]
    const out = settleRunningSteps(steps, 1_213_400)
    expect(out[0].endedAt).toBe(2000)
    expect(out[0].durationMs).toBe(1000)
    expect(out[1].lifecycle).toBe('paused')
    expect(out[1].endedAt).toBe(1500)
    expect(out[2].lifecycle).toBe('done')
    expect(out[2].endedAt).toBeUndefined()
  })

  it('空 / 无 running 时返回原数组引用（不下发新引用）', () => {
    expect(settleRunningSteps(undefined, 100)).toBeUndefined()
    const done: ProcessingStep[] = [{ id: 'x', phase: 'tool', lifecycle: 'done', endedAt: 10 }]
    expect(settleRunningSteps(done, 100)).toBe(done)
  })

  it('嵌套 subSteps 中的 running 步同样收口', () => {
    const steps: ProcessingStep[] = [
      {
        id: 'worker-1', phase: 'worker', lifecycle: 'done', endedAt: 5000,
        subSteps: [
          { id: 'tool-sub', phase: 'tool', lifecycle: 'running', clientStartedAt: 1_000_000 },
        ],
      },
    ]
    const settledAt = 6_000
    const out = settleRunningSteps(steps, settledAt)
    const sub = out[0].subSteps?.[0]
    expect(sub?.lifecycle).toBe('done')
    expect(sub?.endedAt).toBe(settledAt)
    expect(sub?.clientStartedAt).toBeUndefined()
    // 外层已 done 的 worker 保持原 endedAt 不被覆盖
    expect(out[0].endedAt).toBe(5000)
  })
})
