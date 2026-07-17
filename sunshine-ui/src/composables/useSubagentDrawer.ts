import { reactive } from 'vue'
import type { ProcessingStep } from '../api/processingSteps'
import { DRAWER_MIN_WIDTH, usePlanNodeDrawer } from './usePlanNodeDrawer'

const state = reactive({
  open: false,
  step: undefined as ProcessingStep | undefined,
})

/**
 * ReAct spawn_subagent 抽屉：open/close/state，对齐 usePlanNodeDrawer 精简版。
 */
export function useSubagentDrawer() {
  function open(step: ProcessingStep) {
    usePlanNodeDrawer().close()
    state.step = step
    state.open = true
  }

  /** SSE 更新同 id 步骤时刷新抽屉内容（保持打开） */
  function syncIfOpen(step: ProcessingStep) {
    if (state.open && state.step?.id === step.id) {
      state.step = step
    }
  }

  function close() {
    state.open = false
    state.step = undefined
  }

  function isActive(stepId: string | undefined) {
    return !!stepId && state.open && state.step?.id === stepId
  }

  return {
    state,
    open,
    close,
    syncIfOpen,
    isActive,
    drawerWidth: DRAWER_MIN_WIDTH,
  }
}
