import { describe, it, expect, vi } from 'vitest'
import { applyStepDelta, upsertStep, type ProcessingStep } from './processingSteps'

/**
 * 回归：最后一轮 think 的 step_delta(reasoning) 流不应重置 clientStartedAt，
 * 否则 OperationCard live 计时器会在 10s→15s 间反复归零跳变（而非持续递增）。
 */
describe('applyStepDelta clientStartedAt 稳定性', () => {
  it('think 多轮 step_delta 后 clientStartedAt 保持首次锚定，不随 delta 重置', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(1_000_000)
      // 首个 delta 建立 think 步（idx<0 新建路径）
      let steps = applyStepDelta([], { stepId: 'think', channel: 'reasoning', text: '分析' })
      const first = steps[0].clientStartedAt
      expect(first).toBe(1_000_000)

      // 10s 后再来 delta：clientStartedAt 不得被重置为当前时刻
      vi.setSystemTime(1_010_000)
      steps = applyStepDelta(steps, { stepId: 'think', channel: 'reasoning', text: '，继续' })
      expect(steps[0].clientStartedAt).toBe(first)

      // 15s 后又一个 delta：仍锚定首次
      vi.setSystemTime(1_015_000)
      steps = applyStepDelta(steps, { stepId: 'think', channel: 'reasoning', text: '。收尾' })
      expect(steps[0].clientStartedAt).toBe(first)
    } finally {
      vi.useRealTimers()
    }
  })

  it('step 事件与 delta 交错：clientStartedAt 一经建立不被 upsert 覆盖', () => {
    vi.useFakeTimers()
    try {
      vi.setSystemTime(2_000_000)
      let steps = applyStepDelta([], { stepId: 'think', channel: 'reasoning', text: 'x' })
      const anchored = steps[0].clientStartedAt
      // 后端 running step 事件（不带 clientStartedAt）随后到达
      vi.setSystemTime(2_005_000)
      steps = upsertStep(steps, { id: 'think', phase: 'think', lifecycle: 'running', summary: { active: '综合分析' } })
      expect(steps[0].clientStartedAt).toBe(anchored)
    } finally {
      vi.useRealTimers()
    }
  })
})
