import { computed, h, ref, type ComputedRef, type Ref } from 'vue'
import { NIcon, type DropdownOption } from 'naive-ui'
import {
  CloudUploadOutline,
  CopyOutline,
  CreateOutline,
  DocumentTextOutline,
  DownloadOutline,
  DuplicateOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import {
  createWorkflow,
  deleteWorkflow,
  deleteWorkflowVersion,
  exportWorkflowVersion,
  forkWorkflowVersion,
  getWorkflowEditable,
  saveWorkflowDraft,
  setWorkflowEnabled,
  updateWorkflow,
  type WorkflowEditable,
  type WorkflowEntry,
  type WorkflowNodeDefaultsResponse,
  type WorkflowPlan,
  type WorkflowVersion,
} from '../api/workflows'
import { friendlyErrorMessage } from '../api/apiError'
import {
  buildCatalogMeta,
  normalizeWorkflowPlan,
} from '../utils/workflowPlan'
import { applyPlanDefaults } from '../utils/workflowNodeParams'
import type { WorkflowPhase } from '../utils/workflows/workflowsVersionUtils'

const WORKFLOW_ID_PATTERN = /^[\w\u4e00-\u9fff-]+$/

export interface UseWorkflowLifecycleActionsDeps {
  message: {
    success: (content: string) => void
    error: (content: string) => void
    warning: (content: string) => void
  }
  workflows: Ref<WorkflowEntry[]>
  versions: Ref<WorkflowVersion[]>
  selectedId: Ref<string | null>
  selectedVersion: Ref<number | null>
  plan: Ref<WorkflowPlan | null>
  nodeDefaults: Ref<WorkflowNodeDefaultsResponse | null>
  definitionDisplayName: Ref<string>
  definitionDescription: Ref<string>
  catalogExamples: Ref<string>
  catalogIntentAfter: Ref<string>
  workflowPhase: ComputedRef<WorkflowPhase>
  canEditPlan: ComputedRef<boolean>
  canCompareVersions: ComputedRef<boolean>
  refreshPage: () => Promise<void>
  selectWorkflow: (id: string) => Promise<void>
  applyEditable: (data: WorkflowEditable) => void
  loadVersions: (id: string) => Promise<void>
  loadVersionDetail: (id: string, version: number) => Promise<void>
  setSuppressVersionWatch: (v: boolean) => void
  publish: () => Promise<void>
  openVersionDiff: () => void
  triggerImport: () => void
}

/** 列表 CRUD / 版本 fork·导出·菜单 —— 从 useWorkflowsPage 抽出 */
export function useWorkflowLifecycleActions(deps: UseWorkflowLifecycleActionsDeps) {
  const {
    message,
    workflows,
    versions,
    selectedId,
    selectedVersion,
    plan,
    nodeDefaults,
    definitionDisplayName,
    definitionDescription,
    catalogExamples,
    catalogIntentAfter,
    workflowPhase,
    canEditPlan,
    canCompareVersions,
    refreshPage,
    selectWorkflow,
    applyEditable,
    loadVersions,
    loadVersionDetail,
    setSuppressVersionWatch,
    publish,
    openVersionDiff,
    triggerImport,
  } = deps

  const showCreate = ref(false)
  const showEdit = ref(false)
  const showDeleteConfirm = ref(false)
  const showDeleteVersionConfirm = ref(false)
  const createDraft = ref({ id: '', displayName: '', description: '' })
  const createSeedPackage = ref<{ plan: WorkflowPlan; catalog: Record<string, unknown> } | null>(null)
  const editForm = ref({ displayName: '', description: '' })
  const editTarget = ref<WorkflowEntry | null>(null)
  const deleteTarget = ref<WorkflowEntry | null>(null)

  const createIdTrimmed = computed(() => createDraft.value.id.trim())
  const createNameTrimmed = computed(() => createDraft.value.displayName.trim())
  const createDescTrimmed = computed(() => createDraft.value.description.trim())
  const createIdDuplicate = computed(() =>
    createIdTrimmed.value.length > 0
    && workflows.value.some(w => w.id === createIdTrimmed.value),
  )
  const createIdInvalid = computed(() =>
    createIdTrimmed.value.length > 0 && !WORKFLOW_ID_PATTERN.test(createIdTrimmed.value),
  )
  const canConfirmCreate = computed(() =>
    createIdTrimmed.value.length > 0
    && createNameTrimmed.value.length > 0
    && createDescTrimmed.value.length > 0
    && !createIdDuplicate.value
    && !createIdInvalid.value,
  )
  const isDuplicateCreate = computed(() => createSeedPackage.value != null)

  const cardMenuOptions: DropdownOption[] = [
    {
      label: '修改',
      key: 'edit',
      icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
    },
    {
      label: '导出 JSON',
      key: 'export',
      icon: () => h(NIcon, { component: DownloadOutline, size: 14 }),
    },
    { type: 'divider', key: 'divider-card-delete' },
    {
      label: () => h('span', { class: 'more-menu-delete' }, '删除'),
      key: 'delete',
      icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
    },
  ]

  const moreMenuOptions = computed((): DropdownOption[] => {
    const opts: DropdownOption[] = []
    if (workflowPhase.value === 'live' || workflowPhase.value === 'history') {
      const hasDraft = versions.value.some(v => v.status === 'draft')
      if (!hasDraft) {
        opts.push({
          label: '复制为草稿',
          key: 'fork',
          icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
        })
      }
    }
    if (canEditPlan.value || workflowPhase.value === 'live' || workflowPhase.value === 'history') {
      opts.push({
        label: '导入 JSON',
        key: 'import',
        icon: () => h(NIcon, { component: CloudUploadOutline, size: 14 }),
      })
    }
    if (selectedVersion.value != null) {
      opts.push({
        label: '导出 JSON',
        key: 'export',
        icon: () => h(NIcon, { component: DownloadOutline, size: 14 }),
      })
      opts.push({
        label: '复制 JSON',
        key: 'copy-export',
        icon: () => h(NIcon, { component: CopyOutline, size: 14 }),
      })
      opts.push({
        label: '另存为新工作流',
        key: 'duplicate',
        icon: () => h(NIcon, { component: DuplicateOutline, size: 14 }),
      })
    }
    if (canCompareVersions.value && selectedVersion.value != null) {
      opts.push({
        label: '版本对比',
        key: 'diff',
        icon: () => h(NIcon, { component: DocumentTextOutline, size: 14 }),
      })
    }
    if (versions.value.length > 1 && selectedVersion.value != null) {
      if (opts.length > 0) {
        opts.push({ type: 'divider', key: 'divider-before-delete-version' })
      }
      opts.push({
        label: () => h('span', { class: 'more-menu-delete' }, '删除此版本'),
        key: 'delete-version',
        icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
      })
    }
    return opts
  })

  async function toggleEnabled(wf: WorkflowEntry, enabled: boolean) {
    try {
      await setWorkflowEnabled(wf.id, enabled)
      await refreshPage()
      message.success(enabled ? '已启用' : '已关闭')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '切换启用状态失败'))
    }
  }

  function openEdit(wf: WorkflowEntry) {
    editTarget.value = wf
    editForm.value = { displayName: wf.displayName, description: wf.description ?? '' }
    showEdit.value = true
  }

  async function confirmEdit() {
    if (!editTarget.value) return
    if (!editForm.value.displayName.trim() || !editForm.value.description.trim()) {
      message.warning('展示名与描述均不能为空')
      return
    }
    try {
      await updateWorkflow(
        editTarget.value.id,
        editForm.value.displayName.trim(),
        editForm.value.description.trim(),
      )
      showEdit.value = false
      await refreshPage()
      if (selectedId.value === editTarget.value.id) {
        await selectWorkflow(editTarget.value.id)
      }
      message.success('已更新')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '更新失败'))
    }
  }

  function openDelete(wf: WorkflowEntry) {
    deleteTarget.value = wf
    showDeleteConfirm.value = true
  }

  async function confirmDelete() {
    if (!deleteTarget.value) return
    try {
      await deleteWorkflow(deleteTarget.value.id)
      showDeleteConfirm.value = false
      if (selectedId.value === deleteTarget.value.id) {
        selectedId.value = null
        plan.value = null
        versions.value = []
      }
      deleteTarget.value = null
      await refreshPage()
      message.success('已删除')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '删除失败'))
    }
  }

  async function confirmDeleteVersion() {
    if (!selectedId.value || selectedVersion.value == null) return
    try {
      await deleteWorkflowVersion(selectedId.value, selectedVersion.value)
      showDeleteVersionConfirm.value = false
      await loadVersions(selectedId.value)
      const data = await getWorkflowEditable(selectedId.value)
      applyEditable(data)
      await refreshPage()
      message.success('版本已删除')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '删除版本失败'))
    }
  }

  async function forkToDraft() {
    if (!selectedId.value || selectedVersion.value == null) return
    try {
      await forkWorkflowVersion(selectedId.value, selectedVersion.value)
      await loadVersions(selectedId.value)
      const draft = versions.value.find(v => v.status === 'draft')
      if (draft) {
        setSuppressVersionWatch(true)
        selectedVersion.value = draft.version
        setSuppressVersionWatch(false)
        await loadVersionDetail(selectedId.value, draft.version)
      }
      message.success('已复制为草稿')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '复制草稿失败'))
    }
  }

  function downloadWorkflowJson(body: Record<string, unknown>, filename: string) {
    const blob = new Blob([JSON.stringify(body, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  }

  async function exportWorkflowJson(wfId: string, version: number, mode: 'download' | 'copy' = 'download') {
    const body = await exportWorkflowVersion(wfId, version)
    const text = JSON.stringify(body, null, 2)
    if (mode === 'copy') {
      const { copyText } = await import('../utils/stream-markdown/clipboard')
      const ok = await copyText(text)
      if (!ok) throw new Error('复制失败')
      message.success('JSON 已复制到剪贴板')
      return
    }
    downloadWorkflowJson(body, `${wfId}-v${version}.json`)
    message.success('已导出')
  }

  async function exportJson() {
    if (!selectedId.value || selectedVersion.value == null) return
    try {
      await exportWorkflowJson(selectedId.value, selectedVersion.value, 'download')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '导出失败'))
    }
  }

  async function copyExportJson() {
    if (!selectedId.value || selectedVersion.value == null) return
    try {
      await exportWorkflowJson(selectedId.value, selectedVersion.value, 'copy')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '复制失败'))
    }
  }

  function handleCardMenuSelect(wf: WorkflowEntry, key: string) {
    if (key === 'edit') openEdit(wf)
    if (key === 'delete') openDelete(wf)
    if (key === 'export') {
      if (wf.activeVersion <= 0) {
        message.warning('该工作流尚无已发布版本')
        return
      }
      void exportWorkflowJson(wf.id, wf.activeVersion, 'download').catch(e => {
        message.error(friendlyErrorMessage(e, '导出失败'))
      })
    }
  }

  function handleMoreMenuSelect(key: string) {
    switch (key) {
      case 'publish': void publish(); break
      case 'fork': void forkToDraft(); break
      case 'import': triggerImport(); break
      case 'export': void exportJson(); break
      case 'copy-export': void copyExportJson(); break
      case 'duplicate': openDuplicateAsNew(); break
      case 'diff': openVersionDiff(); break
      case 'delete-version': showDeleteVersionConfirm.value = true; break
    }
  }

  async function confirmCreate() {
    if (!canConfirmCreate.value) return
    const seed = createSeedPackage.value
    try {
      const newId = createDraft.value.id.trim()
      const entry = await createWorkflow(
        newId,
        createDraft.value.displayName.trim(),
        createDraft.value.description.trim(),
      )
      if (seed) {
        const normalized = applyPlanDefaults(
          normalizeWorkflowPlan(seed.plan, newId, nodeDefaults.value ?? undefined),
          nodeDefaults.value ?? undefined,
        )
        await saveWorkflowDraft(newId, normalized, seed.catalog)
      }
      closeCreateModal()
      await refreshPage()
      await selectWorkflow(entry.id)
      message.success(seed ? '新工作流已创建并写入当前 Plan' : '工作流已创建')
    } catch (e) {
      message.error(friendlyErrorMessage(e, '创建工作流失败'))
    }
  }

  function suggestDuplicateWorkflowId(baseId: string): string {
    const first = `${baseId}-copy`
    if (!workflows.value.some(w => w.id === first)) return first
    for (let i = 2; i < 100; i += 1) {
      const candidate = `${baseId}-copy${i}`
      if (!workflows.value.some(w => w.id === candidate)) return candidate
    }
    return `${baseId}-copy-${Date.now()}`
  }

  function openDuplicateAsNew() {
    if (!plan.value || !selectedId.value) return
    const id = selectedId.value
    createDraft.value = {
      id: suggestDuplicateWorkflowId(id),
      displayName: `${definitionDisplayName.value.trim() || id} 副本`,
      description: definitionDescription.value.trim(),
    }
    const examples = catalogExamples.value.split('\n').map(s => s.trim()).filter(Boolean)
    createSeedPackage.value = {
      plan: structuredClone(plan.value),
      catalog: buildCatalogMeta(plan.value, examples, catalogIntentAfter.value),
    }
    showCreate.value = true
  }

  function closeCreateModal() {
    showCreate.value = false
    createDraft.value = { id: '', displayName: '', description: '' }
    createSeedPackage.value = null
  }

  function openCreateModal() {
    createDraft.value = { id: '', displayName: '', description: '' }
    createSeedPackage.value = null
    showCreate.value = true
  }

  return {
    showCreate,
    showEdit,
    showDeleteConfirm,
    showDeleteVersionConfirm,
    createDraft,
    editForm,
    canConfirmCreate,
    isDuplicateCreate,
    cardMenuOptions,
    moreMenuOptions,
    toggleEnabled,
    confirmEdit,
    confirmDelete,
    confirmDeleteVersion,
    exportWorkflowJson,
    confirmCreate,
    openDuplicateAsNew,
    closeCreateModal,
    openCreateModal,
    handleCardMenuSelect,
    handleMoreMenuSelect,
  }
}
