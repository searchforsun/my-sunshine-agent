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

  it('think 复用：done→(todo_write)→running(resume) 放行，清 endedAt/durationMs，clientStartedAt 延续最初锚点连续计时', () => {
    vi.useFakeTimers()
    try {
      // think 最初 running（第一轮 reasoning 进行中）
      vi.setSystemTime(3_000_000)
      let steps = applyStepDelta([], { stepId: 'think-3', channel: 'reasoning', text: '第一段' })
      const anchor = steps[0].clientStartedAt
      expect(anchor).toBe(3_000_000)

      // 第一轮 reasoning 输出 todo_write → endReasoningRound → done
      vi.setSystemTime(3_009_239)
      steps = upsertStep(steps, {
        id: 'think-3', phase: 'think', lifecycle: 'done',
        summary: { after: '已完成「查询财务待办详情」的工具结果综合分析' },
        startedAt: 1000, endedAt: 10239, durationMs: 9239,
      })
      // think done 保留锚点（可能随后 resume 复用）
      expect(steps[0].clientStartedAt).toBe(anchor)

      // 第二轮 reasoning（todo_write 后综合输出）→ resume（running）
      vi.setSystemTime(3_009_242)
      steps = upsertStep(steps, {
        id: 'think-3', phase: 'think', lifecycle: 'running',
        summary: { active: '正在综合分析「查询财务待办详情」返回结果' },
        startedAt: 1000, reasoning: null as unknown as undefined,
      })
      const s = steps[0]
      expect(s.lifecycle).toBe('running')
      expect(s.endedAt).toBeUndefined()
      expect(s.durationMs).toBeUndefined()
      // 锚点延续最初（3_000_000），不重置为 resume 时刻 → 计时器从最初起点连续递增
      expect(s.clientStartedAt).toBe(anchor)
      expect(s.reasoning).toBe('第一段')
      expect(s.summary?.active).toBe('正在综合分析「查询财务待办详情」返回结果')

      // 复用后后端继续下发新内容增量（不回放旧段）：在前段基础上累加，不覆盖不清空
      vi.setSystemTime(3_009_250)
      steps = applyStepDelta(steps, { stepId: 'think-3', channel: 'reasoning', text: '，第二段' })
      expect(steps[0].reasoning).toBe('第一段，第二段')
    } finally {
      vi.useRealTimers()
    }
  })

  it('非 think 步仍受硬终态保护：tool 步 done 后拒绝 running', () => {
    const steps: ProcessingStep[] = [{
      id: 'tool-1', phase: 'tool', lifecycle: 'done',
      summary: { after: '已查询' }, startedAt: 1000, endedAt: 2000, durationMs: 1000,
    }]
    const out = upsertStep(steps, {
      id: 'tool-1', phase: 'tool', lifecycle: 'running',
      summary: { active: '重新查询' },
    })
    expect(out[0].lifecycle).toBe('done')
    expect(out[0].endedAt).toBe(2000)
    expect(out[0].durationMs).toBe(1000)
  })
})
