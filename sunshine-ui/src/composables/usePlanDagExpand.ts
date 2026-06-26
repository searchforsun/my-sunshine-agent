import { computed, reactive } from 'vue'
import type { DagNodeView } from '../utils/planGraph'

export interface PlanDagExpandPayload {
  planId: string
  title: string
  userQuery?: string
  nodes: DagNodeView[]
  selectedId?: string
  live?: boolean
  loadingLabel?: string
}

const state = reactive({
  activePlanId: null as string | null,
  title: '',
  userQuery: '',
  nodes: [] as DagNodeView[],
  selectedId: undefined as string | undefined,
  live: false,
  loadingLabel: undefined as string | undefined,
})

let selectHandler: ((node: DagNodeView) => void) | null = null

export function usePlanDagExpand() {
  function open(payload: PlanDagExpandPayload, onSelect: (node: DagNodeView) => void) {
    state.activePlanId = payload.planId
    state.title = payload.title
    state.userQuery = payload.userQuery?.trim() ?? ''
    state.nodes = payload.nodes
    state.selectedId = payload.selectedId
    state.live = !!payload.live
    state.loadingLabel = payload.loadingLabel
    selectHandler = onSelect
  }

  function update(payload: Partial<PlanDagExpandPayload>) {
    if (!state.activePlanId) return
    if (payload.title != null) state.title = payload.title
    if (payload.userQuery != null) state.userQuery = payload.userQuery.trim()
    if (payload.nodes != null) state.nodes = payload.nodes
    if (payload.selectedId !== undefined) state.selectedId = payload.selectedId
    if (payload.live != null) state.live = payload.live
    if (payload.loadingLabel !== undefined) state.loadingLabel = payload.loadingLabel
  }

  function close() {
    state.activePlanId = null
    state.title = ''
    state.userQuery = ''
    state.nodes = []
    state.selectedId = undefined
    state.live = false
    state.loadingLabel = undefined
    selectHandler = null
  }

  function isExpanded(planId: string | undefined) {
    return !!planId && state.activePlanId === planId
  }

  function handleSelect(node: DagNodeView) {
    selectHandler?.(node)
  }

  const isAnyExpanded = computed(() => !!state.activePlanId)

  return { state, open, update, close, isExpanded, handleSelect, isAnyExpanded }
}
