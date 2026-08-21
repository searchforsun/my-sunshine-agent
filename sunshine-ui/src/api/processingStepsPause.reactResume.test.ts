import { describe, it, expect } from 'vitest'
import { resetStepsForReactResume } from './processingStepsPause'
import { applyStepDelta, upsertStep, type ProcessingStep } from './processingSteps'

function think(id: string, reasoning: string, lifecycle: ProcessingStep['lifecycle'] = 'running'): ProcessingStep {
  return { id, phase: 'think', lifecycle, summary: { active: '综合分析' }, reasoning }
}

describe('resetStepsForReactResume（ReAct 续跑同 id 重放）', () => {
  it('清掉 think 步旧半截 reasoning，重放中文 delta 从空白干净落地（不与旧英文叠加）', () => {
    // 恢复前：think-6 残留旧流英文 reasoning
    const before = [think('think-6', 'OK so I have 2 pending inbox items. Let me spawn sub-agents.')]
    const reset = resetStepsForReactResume(before)
    expect(reset[0].reasoning).toBe('')

    // 恢复后：后端重放 think-6 中文 step_delta（纯增量）
    let steps = reset
    for (const chunk of ['用户要求分析', '待审批报销单风险']) {
      steps = applyStepDelta(steps, { stepId: 'think-6', channel: 'reasoning', text: chunk })
    }
    expect(steps[0].reasoning).toBe('用户要求分析待审批报销单风险')
    expect(steps[0].reasoning).not.toContain('OK so I have')
  })

  it('已取消的 subagent 卡续跑保持「已取消」：旧 runId 不重放，不复活为等待中', () => {
    const cancelled: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'paused',
      summary: { active: undefined, after: '已取消' },
    }
    const reset = resetStepsForReactResume([cancelled])
    expect(reset[0].lifecycle).toBe('paused')
    expect(reset[0].summary?.after).toBe('已取消')
  })

  it('真 done 的历史步不重置（reasoning/lifecycle 保留）', () => {
    const done: ProcessingStep = {
      id: 'think-1', phase: 'think', lifecycle: 'done',
      summary: { after: '分析完成' }, reasoning: '已完成的分析',
    }
    const reset = resetStepsForReactResume([done])
    // done 非 paused，仅 think 清 reasoning（中断流式复用语义）；lifecycle 不动
    expect(reset[0].lifecycle).toBe('done')
  })

  it('think 复用（建板后再推理）：running 快照 reasoning 为 null 不清空，delta 续写累加', () => {
    // 后端 step 事件从不带增量 reasoning（aggregator 不落 reasoning），running 快照恒 null。
    // 复用 think-N 时 prev 已是 done + 全量 reasoning，applyStep 不得因 incoming null 清空。
    const before = [think('think', '规划内容：查收件箱、报销单、政策', 'done')]
    // 复用快照：running + 无 reasoning 字段
    let steps = upsertStep(before, { id: 'think', phase: 'think', lifecycle: 'running', summary: { active: '正在规划' } })
    expect(steps[0].reasoning).toBe('规划内容：查收件箱、报销单、政策')
    // 后续 delta 续写累加
    steps = applyStepDelta(steps, { stepId: 'think', channel: 'reasoning', text: '。现在开始第一步' })
    expect(steps[0].reasoning).toBe('规划内容：查收件箱、报销单、政策。现在开始第一步')
  })

  it('intent 步不在 ReAct 重置范围（由 SSE 覆盖）', () => {
    const intent: ProcessingStep = {
      id: 'intent', phase: 'intent', lifecycle: 'paused',
      summary: { after: '已取消' },
    }
    const reset = resetStepsForReactResume([intent])
    expect(reset[0].lifecycle).toBe('paused')
  })
})
