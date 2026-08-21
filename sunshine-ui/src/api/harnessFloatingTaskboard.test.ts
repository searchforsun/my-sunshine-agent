import { describe, it, expect } from 'vitest'
import { hasRealTaskBoardItems, type ProcessingStep, type TaskBoardItemView } from './processingSteps'

/**
 * pro（Planner-Executor / harness）模式下，PlannerActionTool.emitTaskBoardSnapshot
 * 下发的 tasks 快照步 lifecycle 恒为 done（ProcessingStep.done），但带真实任务清单
 * （taskRevision≥1）——悬浮 taskboard 锚点判定须据此成立（ChatView updateFloatingTaskboard
 * 依赖 data-live-taskboard 锚点，锚点条件由 OperationStack isLiveTaskboardAnchor 控制）。
 */
function harnessSnapshotStep(overrides: Partial<ProcessingStep> = {}): ProcessingStep {
  const item: TaskBoardItemView = {
    id: 't1-1',
    content: '调研收件箱待办',
    status: 'in_progress',
  }
  return {
    id: 'tasks',
    phase: 'tasks',
    lifecycle: 'done',
    label: '任务看板',
    metadata: { tasks: [item], taskRevision: 3, taskProgress: '1/3' },
    ...overrides,
  } as ProcessingStep
}

describe('harness tasks 快照步（done）悬浮 taskboard 判定', () => {
  it('done 快照步带 taskRevision≥1 → 视为真实任务清单', () => {
    expect(hasRealTaskBoardItems(harnessSnapshotStep())).toBe(true)
  })

  it('done 快照步有 taskQueue 投影 → 视为真实任务清单（taskQueue 优先）', () => {
    const step = harnessSnapshotStep({
      metadata: {
        taskQueue: [{ id: 't2-1', content: '生成请假单', status: 'pending' }],
        taskProgress: '0/1',
      },
    })
    expect(hasRealTaskBoardItems(step)).toBe(true)
  })

  it('无任务项的占位步 → 不视为真实清单（悬浮不参与）', () => {
    const step = harnessSnapshotStep({ metadata: undefined })
    expect(hasRealTaskBoardItems(step)).toBe(false)
  })
})
