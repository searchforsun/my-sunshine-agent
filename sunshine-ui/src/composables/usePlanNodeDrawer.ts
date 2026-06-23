import { reactive } from 'vue'
import type { ProcessingStep } from '../api/processingSteps'
import type { DagNodeView } from '../utils/planGraph'

export interface PlanNodeDrawerPayload {
  node: DagNodeView
  step?: ProcessingStep
}

const state = reactive({
  open: false,
  node: null as DagNodeView | null,
  step: undefined as ProcessingStep | undefined,
})

export function usePlanNodeDrawer() {
  function open(payload: PlanNodeDrawerPayload) {
    state.node = payload.node
    state.step = payload.step
    state.open = true
  }

  function close() {
    state.open = false
    state.node = null
    state.step = undefined
  }

  return { state, open, close }
}
