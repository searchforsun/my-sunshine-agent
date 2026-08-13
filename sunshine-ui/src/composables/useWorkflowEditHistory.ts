import { computed, ref, watch, type Ref } from 'vue'
import type { WorkflowPlan } from '../api/workflows'

const MAX_UNDO = 50
const DEBOUNCE_MS = 350

export type WorkflowEditSnapshotPayload = {
  plan: WorkflowPlan
  catalogExamples: string
  catalogIntentAfter: string
  definitionDisplayName: string
  definitionDescription: string
  definitionKind: string
  selectedNodeId: string | null
}

type SnapshotSource = {
  canEditPlan: Ref<boolean>
  plan: Ref<WorkflowPlan | null>
  catalogExamples: Ref<string>
  catalogIntentAfter: Ref<string>
  definitionDisplayName: Ref<string>
  definitionDescription: Ref<string>
  definitionKind: Ref<string>
  selectedNodeId: Ref<string | null>
  serializeSnapshot: () => string
  parseSnapshot: (raw: string) => WorkflowEditSnapshotPayload
}

export function useWorkflowEditHistory(source: SnapshotSource) {
  const undoStack = ref<string[]>([])
  const redoStack = ref<string[]>([])
  const historyAnchor = ref('')
  let applyingHistory = false
  let debounceTimer: ReturnType<typeof setTimeout> | null = null

  const canUndo = computed(() => undoStack.value.length > 0 && source.canEditPlan.value)
  const canRedo = computed(() => redoStack.value.length > 0 && source.canEditPlan.value)

  function applyPayload(payload: WorkflowEditSnapshotPayload) {
    source.plan.value = payload.plan
    source.catalogExamples.value = payload.catalogExamples
    source.catalogIntentAfter.value = payload.catalogIntentAfter
    source.definitionDisplayName.value = payload.definitionDisplayName
    source.definitionDescription.value = payload.definitionDescription
    source.definitionKind.value = payload.definitionKind
    source.selectedNodeId.value = payload.selectedNodeId
  }

  function recordStableEdit() {
    if (applyingHistory || !source.canEditPlan.value || !source.plan.value) return
    const cur = source.serializeSnapshot()
    if (!historyAnchor.value) {
      historyAnchor.value = cur
      return
    }
    if (cur === historyAnchor.value) return
    undoStack.value.push(historyAnchor.value)
    if (undoStack.value.length > MAX_UNDO) {
      undoStack.value.shift()
    }
    redoStack.value = []
    historyAnchor.value = cur
  }

  function flushPendingHistory() {
    if (debounceTimer) {
      clearTimeout(debounceTimer)
      debounceTimer = null
      recordStableEdit()
    }
  }

  function scheduleHistoryRecord() {
    if (applyingHistory || !source.canEditPlan.value || !source.plan.value) return
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      recordStableEdit()
    }, DEBOUNCE_MS)
  }

  function resetHistory() {
    if (debounceTimer) {
      clearTimeout(debounceTimer)
      debounceTimer = null
    }
    undoStack.value = []
    redoStack.value = []
    historyAnchor.value = source.plan.value && source.canEditPlan.value
      ? source.serializeSnapshot()
      : ''
  }

  function wrapEditableMutation<T>(mutator: () => T): T {
    flushPendingHistory()
    const result = mutator()
    recordStableEdit()
    return result
  }

  function undo() {
    if (!canUndo.value) return
    flushPendingHistory()
    applyingHistory = true
    redoStack.value.push(historyAnchor.value)
    const prev = undoStack.value.pop()!
    applyPayload(source.parseSnapshot(prev))
    historyAnchor.value = prev
    applyingHistory = false
  }

  function redo() {
    if (!canRedo.value) return
    applyingHistory = true
    undoStack.value.push(historyAnchor.value)
    const next = redoStack.value.pop()!
    applyPayload(source.parseSnapshot(next))
    historyAnchor.value = next
    applyingHistory = false
  }

  watch(
    () => [
      source.plan.value,
      source.catalogExamples.value,
      source.catalogIntentAfter.value,
      source.definitionDisplayName.value,
      source.definitionDescription.value,
      source.definitionKind.value,
      source.canEditPlan.value,
    ] as const,
    () => scheduleHistoryRecord(),
    { deep: true },
  )

  return {
    canUndo,
    canRedo,
    undo,
    redo,
    resetHistory,
    wrapEditableMutation,
    flushPendingHistory,
  }
}
