import { computed, ref, type ComputedRef, type Ref } from 'vue'
import {
  importWorkflowPackage,
  validateWorkflowPlan,
  type WorkflowEntry,
  type WorkflowNodeDefaultsResponse,
  type WorkflowPlan,
} from '../api/workflows'
import { friendlyErrorMessage } from '../api/apiError'
import {
  collectBusinessNodeValidationIssues,
  normalizeWorkflowPlan,
} from '../utils/workflowPlan'
import { collectRetryValidationIssues } from '../utils/workflowNodeParams'
import { validatePlanTopologyLocally } from '../utils/workflowPlanValidation'

const WORKFLOW_ID_PATTERN = /^[\w\u4e00-\u9fff-]+$/

export interface UseWorkflowImportActionsDeps {
  message: {
    success: (content: string) => void
    error: (content: string) => void
  }
  workflows: Ref<WorkflowEntry[]>
  selectedId: Ref<string | null>
  nodeDefaults: Ref<WorkflowNodeDefaultsResponse | null>
  definitionDisplayName: Ref<string>
  definitionDescription: Ref<string>
  refreshPage: () => Promise<void>
  selectWorkflow: (id: string) => Promise<void>
}

/**
 * Studio 导入 JSON 预览 / 覆盖·新建 —— 从 useWorkflowsPage 抽出以降低上帝类体积。
 * 本地拓扑校验零延迟；远端 validate 为发布同款权威。
 */
export function useWorkflowImportActions(deps: UseWorkflowImportActionsDeps) {
  const {
    message,
    workflows,
    selectedId,
    nodeDefaults,
    definitionDisplayName,
    definitionDescription,
    refreshPage,
    selectWorkflow,
  } = deps

  const showImportModal = ref(false)
  const importPreviewLoading = ref(false)
  const importPreviewBody = ref<Record<string, unknown> | null>(null)
  const importPreviewIssues = ref<string[]>([])
  const importMode = ref<'overwrite' | 'new'>('overwrite')
  const importDraft = ref({ id: '', displayName: '', description: '' })
  const importInputRef = ref<HTMLInputElement | null>(null)

  const importIdTrimmed = computed(() => importDraft.value.id.trim())
  const importNameTrimmed = computed(() => importDraft.value.displayName.trim())
  const importDescTrimmed = computed(() => importDraft.value.description.trim())
  const importIdDuplicate = computed(() =>
    importMode.value === 'new'
    && importIdTrimmed.value.length > 0
    && workflows.value.some(w => w.id === importIdTrimmed.value),
  )
  const importIdInvalid = computed(() =>
    importMode.value === 'new'
    && importIdTrimmed.value.length > 0
    && !WORKFLOW_ID_PATTERN.test(importIdTrimmed.value),
  )
  const canConfirmImport = computed(() => {
    if (importPreviewIssues.value.length > 0) return false
    if (!importPreviewBody.value?.plan) return false
    if (importMode.value === 'overwrite') return selectedId.value != null
    return importIdTrimmed.value.length > 0
      && importNameTrimmed.value.length > 0
      && importDescTrimmed.value.length > 0
      && !importIdDuplicate.value
      && !importIdInvalid.value
  })

  function resolveImportTargetId(): string | null {
    if (importMode.value === 'overwrite') return selectedId.value
    return importIdTrimmed.value || null
  }

  function extractImportMeta(body: Record<string, unknown>) {
    const id = typeof body.workflowId === 'string' ? body.workflowId.trim() : ''
    const displayName = typeof body.displayName === 'string' ? body.displayName.trim() : id
    const description = typeof body.description === 'string' ? body.description.trim() : ''
    return { id, displayName, description }
  }

  function suggestImportWorkflowId(baseId?: string): string {
    const base = (baseId?.trim() || 'imported-flow').replace(/[^\w\u4e00-\u9fff-]/g, '-')
    if (!workflows.value.some(w => w.id === base)) return base
    for (let i = 2; i < 100; i += 1) {
      const candidate = `${base}-${i}`
      if (!workflows.value.some(w => w.id === candidate)) return candidate
    }
    return `${base}-${Date.now()}`
  }

  function inferImportMode(body: Record<string, unknown>): 'overwrite' | 'new' {
    const meta = extractImportMeta(body)
    if (!selectedId.value) return 'new'
    if (meta.id && meta.id !== selectedId.value) return 'new'
    return 'overwrite'
  }

  async function validateImportPlanBody(body: Record<string, unknown>, targetId: string) {
    const planRaw = body.plan
    if (!planRaw || typeof planRaw !== 'object') {
      importPreviewIssues.value = ['缺少 plan 字段']
      return
    }
    const normalized = normalizeWorkflowPlan(
      planRaw as WorkflowPlan,
      targetId,
      nodeDefaults.value ?? undefined,
    )
    // 零延迟本地规则（拓扑/业务/重试）；无本地问题再打服务端 WorkflowPlanValidator
    const localIssues = [
      ...validatePlanTopologyLocally(normalized),
      ...collectBusinessNodeValidationIssues(normalized),
      ...collectRetryValidationIssues(normalized, null, true),
    ]
    if (localIssues.length > 0) {
      importPreviewIssues.value = localIssues
      return
    }
    const remote = await validateWorkflowPlan(normalized)
    importPreviewIssues.value = remote.issues ?? []
  }

  async function refreshImportValidation() {
    const body = importPreviewBody.value
    const targetId = resolveImportTargetId()
    if (!body) return
    if (!targetId) {
      importPreviewIssues.value = importMode.value === 'new' ? ['请填写 Workflow ID'] : ['请先选择工作流']
      return
    }
    importPreviewLoading.value = true
    try {
      await validateImportPlanBody(body, targetId)
    } catch (e) {
      importPreviewIssues.value = [friendlyErrorMessage(e, '校验失败')]
    } finally {
      importPreviewLoading.value = false
    }
  }

  async function prepareImportPreview(body: Record<string, unknown>) {
    importPreviewBody.value = body
    importPreviewIssues.value = []
    const meta = extractImportMeta(body)
    importMode.value = inferImportMode(body)
    importDraft.value = {
      id: meta.id || suggestImportWorkflowId(),
      displayName: meta.displayName || meta.id || '导入工作流',
      description: meta.description || '从 JSON 包导入',
    }
    showImportModal.value = true
    await refreshImportValidation()
  }

  function closeImportModal() {
    showImportModal.value = false
    importPreviewBody.value = null
    importPreviewIssues.value = []
    importDraft.value = { id: '', displayName: '', description: '' }
    importMode.value = 'overwrite'
  }

  async function confirmImportPreview() {
    if (!importPreviewBody.value || !canConfirmImport.value) return
    const targetId = resolveImportTargetId()
    if (!targetId) return
    importPreviewLoading.value = true
    try {
      const body: Record<string, unknown> = {
        ...importPreviewBody.value,
        workflowId: targetId,
        displayName: importMode.value === 'new'
          ? importNameTrimmed.value
          : (importPreviewBody.value.displayName ?? definitionDisplayName.value),
        description: importMode.value === 'new'
          ? importDescTrimmed.value
          : (importPreviewBody.value.description ?? definitionDescription.value),
      }
      await importWorkflowPackage(body)
      closeImportModal()
      await refreshPage()
      await selectWorkflow(targetId)
      message.success(importMode.value === 'new' ? '已导入为新工作流' : '已导入为草稿')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '导入失败'))
    } finally {
      importPreviewLoading.value = false
    }
  }

  function setImportMode(mode: 'overwrite' | 'new') {
    if (importMode.value === mode) return
    importMode.value = mode
    void refreshImportValidation()
  }

  function triggerImport() {
    importInputRef.value?.click()
  }

  async function handleImportFile(ev: Event) {
    const input = ev.target as HTMLInputElement
    const file = input.files?.[0]
    input.value = ''
    if (!file) return
    try {
      const text = await file.text()
      const body = JSON.parse(text) as Record<string, unknown>
      await prepareImportPreview(body)
    } catch (e) {
      message.error(friendlyErrorMessage(e, 'JSON 解析失败'))
    }
  }

  function bindImportInputRef(el: unknown) {
    importInputRef.value = el instanceof HTMLInputElement ? el : null
  }

  return {
    showImportModal,
    importPreviewLoading,
    importPreviewBody,
    importPreviewIssues,
    importMode,
    importDraft,
    canConfirmImport: canConfirmImport as ComputedRef<boolean>,
    confirmImportPreview,
    closeImportModal,
    setImportMode,
    refreshImportValidation,
    bindImportInputRef,
    handleImportFile,
    triggerImport,
  }
}
