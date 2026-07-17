import { computed, reactive } from 'vue'
import type { PlanGraph } from '../api/executionPlans'
import type { DagNodeView } from '../utils/planGraph'

export interface PlanDagExpandPayload {
  planId: string
  title: string
  userQuery?: string
  graph: PlanGraph
  nodes: DagNodeView[]
  selectedId?: string
  live?: boolean
  loadingLabel?: string
}

const state = reactive({
  activePlanId: null as string | null,
  title: '',
  userQuery: '',
  graph: null as PlanGraph | null,
  nodes: [] as DagNodeView[],
  selectedId: undefined as string | undefined,
  live: false,
  loadingLabel: undefined as string | undefined,
})

/** 按 planId 注册，避免放大层与 PlanWorkflowPanel 闭包 handler 脱节 */
const selectHandlers = new Map<string, (node: DagNodeView) => void>()

export function registerPlanDagSelectHandler(
  planId: string,
  onSelect: (node: DagNodeView) => void,
) {
  const id = planId.trim()
  if (!id) return
  selectHandlers.set(id, onSelect)
}

export function unregisterPlanDagSelectHandler(planId: string) {
  const id = planId.trim()
  if (!id) return
  selectHandlers.delete(id)
}

export function usePlanDagExpand() {
  function open(payload: PlanDagExpandPayload, onSelect: (node: DagNodeView) => void) {
    state.activePlanId = payload.planId
    state.title = payload.title
    state.userQuery = payload.userQuery?.trim() ?? ''
    state.graph = payload.graph
    state.nodes = payload.nodes
    state.selectedId = payload.selectedId
    state.live = !!payload.live
    state.loadingLabel = payload.loadingLabel
    registerPlanDagSelectHandler(payload.planId, onSelect)
  }

  function update(payload: Partial<PlanDagExpandPayload>) {
    if (!state.activePlanId) return
    if (payload.title != null) state.title = payload.title
    if (payload.userQuery != null) state.userQuery = payload.userQuery.trim()
    if (payload.graph != null) state.graph = payload.graph
    if (payload.nodes != null) state.nodes = payload.nodes
    if (payload.selectedId !== undefined) state.selectedId = payload.selectedId
    if (payload.live != null) state.live = payload.live
    if (payload.loadingLabel !== undefined) state.loadingLabel = payload.loadingLabel
  }

  function close() {
    state.activePlanId = null
    state.title = ''
    state.userQuery = ''
    state.graph = null
    state.nodes = []
    state.selectedId = undefined
    state.live = false
    state.loadingLabel = undefined
  }

  function isExpanded(planId: string | undefined) {
    return !!planId && state.activePlanId === planId
  }

  function handleSelect(node: DagNodeView) {
    state.selectedId = node.id
    const pid = state.activePlanId
    if (!pid) return
    selectHandlers.get(pid)?.(node)
  }

  /** 放大态 sync 时重绑当前 plan 的 handler */
  function bindSelect(planId: string, onSelect: (node: DagNodeView) => void) {
    if (!state.activePlanId || state.activePlanId !== planId) return
    registerPlanDagSelectHandler(planId, onSelect)
  }

  const isAnyExpanded = computed(() => !!state.activePlanId)

  return { state, open, update, close, isExpanded, handleSelect, bindSelect, isAnyExpanded }
}
