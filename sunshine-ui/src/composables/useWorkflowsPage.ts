import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch, type ComputedRef, type InjectionKey, type Ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useMessage, type DropdownOption } from 'naive-ui'
import {
  createWorkflow,
  deleteWorkflow,
  deleteWorkflowVersion,
  exportWorkflowVersion,
  fetchWorkflowNodeDefaults,
  forkWorkflowVersion,
  getWorkflowEditable,
  getWorkflowVersion,
  importWorkflowPackage,
  listWorkflows,
  listWorkflowVersions,
  publishWorkflow,
  saveWorkflowDraft,
  setWorkflowEnabled,
  updateWorkflow,
  validateWorkflowPlan,
  type WorkflowEditable,
  type WorkflowEntry,
  type WorkflowNodeDefaultsResponse,
  type WorkflowPlan,
  type WorkflowVersion,
} from '../api/workflows'
import { friendlyErrorMessage } from '../api/apiError'
import { listToolCatalog, type ToolCatalogEntry } from '../api/tools'
import { listSkillCatalogIndex, type SkillCatalogIndexEntry } from '../api/skills'
import { listKbs, type KnowledgeBase } from '../api/ragAdmin'
import { useTenantPreference } from './useTenantPreference'
import {
  buildCatalogMeta,
  buildLinearRagQaPlan,
  buildLinearToolAgentPlan,
  buildFinanceListPlan,
  buildFinanceSummaryPlan,
  buildParallelDualRagPlan,
  buildPreviewDagNodes,
  businessNodeOrder,
  collectBusinessNodeValidationIssues,
  FLOW_CONFIG_SELECTION,
  insertBusinessNode,
  isParallelPlan,
  normalizeWorkflowPlan,
  removeBusinessNode,
  resolveInsertIndexAfterSelection,
  updateBusinessNode,
  type WorkflowBusinessNodeType,
} from '../utils/workflowPlan'
import type { WorkflowTemplateId } from '../utils/workflowTemplates'
import {
  type WorkflowPhase,
  isWorkflowSwitchDisabled,
  resolveVersionStatus,
  versionOptionLabel,
  versionStatusLabel,
  versionStatusTagType,
} from '../utils/workflows/workflowsVersionUtils'
import {
  autoLayoutPlan,
  addParallelBranchPlan,
  removePlanGraphNode,
} from '../utils/workflowDagLayout'
import { useWorkflowEditHistory } from './useWorkflowEditHistory'
import { useWorkflowsRouteState } from './useWorkflowsRouteState'
import {
  applyPlanDefaults,
  collectRetryValidationIssues,
  resolveNodeDefaults,
} from '../utils/workflowNodeParams'
import { validatePlanTopologyLocally, extractValidationIssueNodeIds } from '../utils/workflowPlanValidation'

export const WORKFLOWS_PAGE_KEY: InjectionKey<WorkflowsPageApi> = Symbol('workflowsPage')

const WORKFLOW_ID_PATTERN = /^[\w\u4e00-\u9fff-]+$/

type UnwrapPageMember<T> =
  T extends Ref<infer V> ? V :
  T extends ComputedRef<infer V> ? V :
  T extends (...args: infer A) => infer R ? (...args: A) => R :
  T

type WorkflowsPageComposable = ReturnType<typeof useWorkflowsPageImpl>

export type WorkflowsPageApi = {
  [K in keyof WorkflowsPageComposable]: UnwrapPageMember<WorkflowsPageComposable[K]>
}

export function useWorkflowsPage(): WorkflowsPageApi {
  return useWorkflowsPageImpl() as WorkflowsPageApi
}

function useWorkflowsPageImpl() {
  const message = useMessage()
  const route = useRoute()
  const router = useRouter()
  const { tenantId } = useTenantPreference()
  const { readWorkflowId, syncWorkflowId } = useWorkflowsRouteState()
  const loading = ref(false)
  const detailLoading = ref(false)
  const saving = ref(false)
  const publishing = ref(false)
  const validating = ref(false)
  const validationIssues = ref<string[]>([])
  const workflows = ref<WorkflowEntry[]>([])
  const versions = ref<WorkflowVersion[]>([])
  const workflowSearch = ref('')
  const selectedId = ref<string | null>(null)
  const selectedVersion = ref<number | null>(null)
  const committedVersion = ref<number | null>(null)
  const plan = ref<WorkflowPlan | null>(null)
  const catalogExamples = ref('')
  const catalogIntentAfter = ref('')
  const definitionDisplayName = ref('')
  const definitionDescription = ref('')
  const editStatus = ref('draft')
  const editVersion = ref(0)
  const selectedNodeId = ref<string | null>(null)
  const toolOptions = ref<ToolCatalogEntry[]>([])
  const skillOptions = ref<SkillCatalogIndexEntry[]>([])
  const kbOptions = ref<KnowledgeBase[]>([])
  const nodeDefaults = ref<WorkflowNodeDefaultsResponse | null>(null)
  const showCreate = ref(false)
  const showEdit = ref(false)
  const showDeleteConfirm = ref(false)
  const showDeleteVersionConfirm = ref(false)
  const showTemplateModal = ref(false)
  const showImportModal = ref(false)
  const importPreviewLoading = ref(false)
  const importPreviewBody = ref<Record<string, unknown> | null>(null)
  const importPreviewIssues = ref<string[]>([])
  const importMode = ref<'overwrite' | 'new'>('overwrite')
  const importDraft = ref({ id: '', displayName: '', description: '' })
  const createDraft = ref({ id: '', displayName: '', description: '' })
  const createSeedPackage = ref<{ plan: WorkflowPlan; catalog: Record<string, unknown> } | null>(null)
  const editForm = ref({ displayName: '', description: '' })
  const editTarget = ref<WorkflowEntry | null>(null)
  const deleteTarget = ref<WorkflowEntry | null>(null)
  const savedSnapshot = ref('')
  const importInputRef = ref<HTMLInputElement | null>(null)
  let suppressVersionWatch = false
  let suppressRouteWatch = false

  const selectedWorkflow = computed(() =>
    workflows.value.find(w => w.id === selectedId.value) ?? null,
  )

  const activeVersionNum = computed(() => selectedWorkflow.value?.activeVersion ?? null)

  const selectedVersionEntry = computed(() =>
    versions.value.find(v => v.version === selectedVersion.value) ?? null,
  )

  const workflowPhase = computed((): WorkflowPhase => {
    const ver = selectedVersionEntry.value
    if (!ver) return 'setup'
    if (ver.status === 'draft') return 'draft'
    if (ver.version === activeVersionNum.value) return 'live'
    return 'history'
  })

  const versionStatus = computed(() =>
    resolveVersionStatus(selectedVersionEntry.value, activeVersionNum.value),
  )

  const versionOptions = computed(() =>
    versions.value.map(v => ({
      label: versionOptionLabel(v),
      value: v.version,
    })),
  )

  const filteredWorkflows = computed(() => {
    const q = workflowSearch.value.trim().toLowerCase()
    if (!q) return workflows.value
    return workflows.value.filter(w =>
      w.id.toLowerCase().includes(q)
      || w.displayName.toLowerCase().includes(q)
      || (w.description ?? '').toLowerCase().includes(q),
    )
  })

  const businessNodes = computed(() =>
    plan.value ? businessNodeOrder(plan.value) : [],
  )

  const selectedNode = computed(() => {
    if (!plan.value || !selectedNodeId.value || selectedNodeId.value === FLOW_CONFIG_SELECTION) return null
    return plan.value.nodes.find(n => n.id === selectedNodeId.value) ?? null
  })

  const isFlowConfigSelected = computed(() => selectedNodeId.value === FLOW_CONFIG_SELECTION)

  const previewNodes = computed(() =>
    plan.value ? buildPreviewDagNodes(plan.value) : [],
  )

  const isParallelWorkflow = computed(() =>
    plan.value ? isParallelPlan(plan.value) : false,
  )

  const validationHighlightNodeIds = computed(() =>
    extractValidationIssueNodeIds(validationIssues.value),
  )

  const canTryInChat = computed(() =>
    !!selectedWorkflow.value?.enabled && !!selectedId.value,
  )

  const canCompareVersions = computed(() => versions.value.length >= 2 && !!selectedId.value)

  const canApplyParallelTemplate = computed(() =>
    canEditPlan.value && plan.value != null && !isParallelWorkflow.value,
  )

  const canEditPlan = computed(() => workflowPhase.value === 'draft' || workflowPhase.value === 'setup')

  const isDirty = computed(() => {
    if (!plan.value || !canEditPlan.value) return false
    return JSON.stringify(snapshot()) !== savedSnapshot.value
  })

  const showSaveDraftButton = computed(() => canEditPlan.value && isDirty.value)
  const showPublishButton = computed(() =>
    workflowPhase.value === 'draft'
    || workflowPhase.value === 'setup'
    || workflowPhase.value === 'history',
  )

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

  const cardMenuOptions: DropdownOption[] = [
    { label: '修改', key: 'edit' },
    { label: '导出 JSON', key: 'export' },
    { label: '删除', key: 'delete' },
  ]

  const moreMenuOptions = computed((): DropdownOption[] => {
    const opts: DropdownOption[] = []
    if (workflowPhase.value === 'live' || workflowPhase.value === 'history') {
      const hasDraft = versions.value.some(v => v.status === 'draft')
      if (!hasDraft) {
        opts.push({ label: '复制为草稿', key: 'fork' })
      }
    }
    if (canEditPlan.value || workflowPhase.value === 'live' || workflowPhase.value === 'history') {
      opts.push({ label: '导入 JSON', key: 'import' })
    }
    if (selectedVersion.value != null) {
      opts.push({ label: '导出 JSON', key: 'export' })
      opts.push({ label: '复制 JSON', key: 'copy-export' })
      opts.push({ label: '另存为新工作流', key: 'duplicate' })
    }
    if (canCompareVersions.value && selectedVersion.value != null) {
      opts.push({ label: '版本对比', key: 'diff' })
    }
    if (versions.value.length > 1 && selectedVersion.value != null) {
      opts.push({ label: '删除此版本', key: 'delete-version' })
    }
    return opts
  })

  function snapshot() {
    const examples = catalogExamples.value
      .split('\n')
      .map(s => s.trim())
      .filter(Boolean)
    return JSON.stringify({
      plan: plan.value,
      catalog: buildCatalogMeta(plan.value!, examples, catalogIntentAfter.value),
      definition: {
        displayName: definitionDisplayName.value.trim(),
        description: definitionDescription.value.trim(),
      },
      selectedNodeId: selectedNodeId.value,
    })
  }

  function parseSnapshot(raw: string) {
    const data = JSON.parse(raw) as {
      plan: WorkflowPlan
      catalog: { examples?: string[]; intentAfter?: string }
      definition: { displayName: string; description: string }
      selectedNodeId?: string | null
    }
    return {
      plan: structuredClone(data.plan),
      catalogExamples: Array.isArray(data.catalog?.examples)
        ? data.catalog.examples.join('\n')
        : '',
      catalogIntentAfter: typeof data.catalog?.intentAfter === 'string' ? data.catalog.intentAfter : '',
      definitionDisplayName: data.definition.displayName,
      definitionDescription: data.definition.description,
      selectedNodeId: data.selectedNodeId ?? FLOW_CONFIG_SELECTION,
    }
  }

  const editHistory = useWorkflowEditHistory({
    canEditPlan,
    plan,
    catalogExamples,
    catalogIntentAfter,
    definitionDisplayName,
    definitionDescription,
    selectedNodeId,
    serializeSnapshot: snapshot,
    parseSnapshot,
  })

  function syncDefinitionForm(wfId: string) {
    const wf = workflows.value.find(w => w.id === wfId)
    definitionDisplayName.value = wf?.displayName ?? ''
    definitionDescription.value = wf?.description ?? ''
  }

  async function persistDefinitionIfDirty(): Promise<void> {
    if (!selectedId.value) return
    const wf = workflows.value.find(w => w.id === selectedId.value)
    if (!wf) return
    const name = definitionDisplayName.value.trim()
    const desc = definitionDescription.value.trim()
    if (!name || !desc) return
    if (name === wf.displayName && desc === (wf.description ?? '')) return
    await updateWorkflow(selectedId.value, name, desc)
    const idx = workflows.value.findIndex(w => w.id === selectedId.value)
    if (idx >= 0) {
      workflows.value[idx] = { ...workflows.value[idx], displayName: name, description: desc }
    }
  }

  function applyEditable(data: WorkflowEditable) {
    const wfId = data.workflowId
    const normalized = applyPlanDefaults(normalizeWorkflowPlan(data.plan, wfId), nodeDefaults.value)
    plan.value = normalized
    editStatus.value = data.status
    editVersion.value = data.version
    selectedVersion.value = data.version
    committedVersion.value = data.version
    const examples = Array.isArray(data.catalog?.examples)
      ? (data.catalog.examples as string[]).join('\n')
      : ''
    catalogExamples.value = examples
    catalogIntentAfter.value = typeof data.catalog?.intentAfter === 'string'
      ? data.catalog.intentAfter
      : ''
    syncDefinitionForm(wfId)
    savedSnapshot.value = snapshot()
    validationIssues.value = []
    selectedNodeId.value = FLOW_CONFIG_SELECTION
    editHistory.resetHistory()
  }

  async function loadCatalogOptions() {
    const [toolsResult, skillsResult, defaultsResult, kbsResult] = await Promise.allSettled([
      listToolCatalog(undefined, false),
      listSkillCatalogIndex(),
      fetchWorkflowNodeDefaults(),
      listKbs(tenantId.value),
    ])
    if (toolsResult.status === 'fulfilled') {
      toolOptions.value = toolsResult.value.filter(t => t.enabled)
    } else {
      console.warn('[Workflows] tool catalog load failed', toolsResult.reason)
      toolOptions.value = []
    }
    if (skillsResult.status === 'fulfilled') {
      skillOptions.value = skillsResult.value.filter(s => s.enabled)
    } else {
      console.warn('[Workflows] skill catalog load failed', skillsResult.reason)
      skillOptions.value = []
    }
    if (defaultsResult.status === 'fulfilled') {
      nodeDefaults.value = defaultsResult.value
    } else {
      console.warn('[Workflows] node defaults load failed', defaultsResult.reason)
      nodeDefaults.value = null
    }
    if (kbsResult.status === 'fulfilled') {
      kbOptions.value = kbsResult.value
    } else {
      console.warn('[Workflows] kb list load failed', kbsResult.reason)
      kbOptions.value = []
    }
  }

  function collectPublishValidationIssues(): string[] {
    if (!plan.value) return []
    return [
      ...validatePlanTopologyLocally(plan.value),
      ...collectBusinessNodeValidationIssues(plan.value),
      ...collectRetryValidationIssues(plan.value, null, true),
    ]
  }

  async function persistDraft(): Promise<void> {
    if (!plan.value || !selectedId.value) return
    const wfId = selectedId.value
    const normalized = normalizeWorkflowPlan(plan.value, wfId)
    const examples = catalogExamples.value.split('\n').map(s => s.trim()).filter(Boolean)
    const catalog = buildCatalogMeta(normalized, examples, catalogIntentAfter.value)
    await persistDefinitionIfDirty()
    await saveWorkflowDraft(wfId, normalized, catalog)
    await loadVersions(wfId)
    const data = await getWorkflowVersion(wfId, selectedVersion.value ?? editVersion.value)
    applyEditable(data)
  }

  async function runPublishValidation(): Promise<string[]> {
    const localIssues = collectPublishValidationIssues()
    if (localIssues.length > 0) return localIssues
    if (!plan.value || !selectedId.value) return []
    const normalized = normalizeWorkflowPlan(plan.value, selectedId.value)
    const result = await validateWorkflowPlan(normalized)
    return result.issues ?? []
  }

  async function refreshPage() {
    loading.value = true
    try {
      workflows.value = await listWorkflows()
      await reconcileSelectionFromRoute()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载工作流列表失败'))
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  async function loadWorkflowDetail(id: string) {
    selectedId.value = id
    loading.value = true
    try {
      await loadVersions(id)
      const data = await getWorkflowEditable(id)
      applyEditable(data)
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载工作流详情失败'))
      console.error(e)
      plan.value = null
    } finally {
      loading.value = false
    }
  }

  async function reconcileSelectionFromRoute() {
    if (workflows.value.length === 0) {
      selectedId.value = null
      plan.value = null
      versions.value = []
      if (readWorkflowId()) {
        suppressRouteWatch = true
        syncWorkflowId(null)
        await nextTick()
        suppressRouteWatch = false
      }
      return
    }
    const routeId = readWorkflowId()
    const targetId = routeId && workflows.value.some(w => w.id === routeId)
      ? routeId
      : workflows.value[0].id
    const shouldSyncRoute = routeId !== targetId
    if (shouldSyncRoute) suppressRouteWatch = true
    if (shouldSyncRoute) syncWorkflowId(targetId)
    if (selectedId.value === targetId && plan.value) {
      if (shouldSyncRoute) {
        await nextTick()
        suppressRouteWatch = false
      }
      return
    }
    try {
      await loadWorkflowDetail(targetId)
    } finally {
      if (shouldSyncRoute) {
        await nextTick()
        suppressRouteWatch = false
      }
    }
  }

  async function loadVersions(id: string) {
    versions.value = await listWorkflowVersions(id)
  }

  async function loadVersionDetail(id: string, version: number) {
    detailLoading.value = true
    try {
      const data = version === selectedVersion.value && plan.value
        ? await getWorkflowVersion(id, version)
        : await getWorkflowVersion(id, version)
      applyEditable(data)
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载版本失败'))
      throw e
    } finally {
      detailLoading.value = false
    }
  }

  async function selectWorkflow(id: string) {
    if (selectedId.value === id && plan.value) return
    if (!(await flushDraftBeforeLeave())) {
      message.warning('自动保存失败，请手动保存后再切换')
      return
    }
    await loadWorkflowDetail(id)
    suppressRouteWatch = true
    syncWorkflowId(id)
    suppressRouteWatch = false
  }

  watch(selectedVersion, async (ver, prev) => {
    if (suppressVersionWatch || ver == null || !selectedId.value) return
    if (ver === prev) return
    if (!(await flushDraftBeforeLeave())) {
      suppressVersionWatch = true
      selectedVersion.value = committedVersion.value
      suppressVersionWatch = false
      message.warning('自动保存失败，请手动保存后再切换版本')
      return
    }
    try {
      await loadVersionDetail(selectedId.value, ver)
    } catch {
      suppressVersionWatch = true
      selectedVersion.value = committedVersion.value
      suppressVersionWatch = false
    }
  })

  function applyWorkflowTemplatePlan(
    buildPlan: (workflowId: string, defaults: ReturnType<typeof resolveNodeDefaults>) => WorkflowPlan,
    successMessage: string,
  ) {
    if (!plan.value || !selectedId.value || !canEditPlan.value) return
    editHistory.wrapEditableMutation(() => {
      const defaults = resolveNodeDefaults(nodeDefaults.value)
      plan.value = autoLayoutPlan(
        applyPlanDefaults(buildPlan(selectedId.value!, defaults), defaults),
      )
      selectedNodeId.value = FLOW_CONFIG_SELECTION
      validationIssues.value = []
    })
    message.info(successMessage)
  }

  function addParallelBranch() {
    if (!plan.value || !canEditPlan.value) return
    const result = addParallelBranchPlan(plan.value, nodeDefaults.value)
    if (!result.ok) {
      message.warning(result.reason)
      return
    }
    editHistory.wrapEditableMutation(() => {
      plan.value = result.plan
      selectedNodeId.value = result.nodeId
    })
    message.success('已添加并行 RAG 分支')
  }

  function openInChat() {
    const id = selectedId.value
    if (!id) return
    if (!selectedWorkflow.value?.enabled) {
      message.warning('请先在列表中启用该工作流')
      return
    }
    void router.push({ name: 'chat', query: { workflow: id } })
  }

  function openVersionDiff() {
    const id = selectedId.value
    const current = selectedVersion.value
    if (!id || current == null || versions.value.length < 2) return
    const sorted = [...versions.value].sort((a, b) => a.version - b.version)
    const idx = sorted.findIndex(v => v.version === current)
    const from = idx > 0 ? sorted[idx - 1].version : sorted[0].version
    const to = current
    void router.push({
      name: 'workflow-diff',
      params: { workflowId: id },
      query: { from: String(from), to: String(to) },
    })
  }

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

  function applyParallelDualRagTemplate() {
    if (!plan.value || !selectedId.value || !canEditPlan.value) return
    if (isParallelWorkflow.value) {
      message.warning('当前已是并行 DAG，无需重复应用')
      return
    }
    applyWorkflowTemplatePlan(
      buildParallelDualRagPlan,
      '已应用并行双检索模板（start → 双 RAG → join → answer）',
    )
  }

  function applyWorkflowTemplate(templateId: WorkflowTemplateId) {
    switch (templateId) {
      case 'linear-rag-qa':
        applyWorkflowTemplatePlan(
          buildLinearRagQaPlan,
          '已应用知识库问答模板（start → RAG → answer）',
        )
        break
      case 'linear-tool-agent':
        applyWorkflowTemplatePlan(
          buildLinearToolAgentPlan,
          '已应用工具 + Agent 模板（start → Tool → Agent → answer）',
        )
        break
      case 'parallel-dual-rag':
        applyParallelDualRagTemplate()
        break
      case 'finance-list':
        applyWorkflowTemplatePlan(
          buildFinanceListPlan,
          '已应用财务待办查询模板（start → Tool → answer）',
        )
        break
      case 'finance-summary':
        applyWorkflowTemplatePlan(
          buildFinanceSummaryPlan,
          '已应用财务汇总统计模板（start → Tool → answer）',
        )
        break
      default:
        message.warning('未知模板')
    }
  }

  function addNode(type: WorkflowBusinessNodeType) {
    if (!plan.value || !selectedId.value || !canEditPlan.value) return
    editHistory.wrapEditableMutation(() => {
      const defaults = resolveNodeDefaults(nodeDefaults.value)
      const insertAt = resolveInsertIndexAfterSelection(plan.value!, selectedNodeId.value)
      plan.value = insertBusinessNode(plan.value!, type, defaults, insertAt)
      const added = businessNodeOrder(plan.value!)[insertAt]
      if (added) selectedNodeId.value = added.id
    })
  }

  function removeNode(nodeId: string) {
    if (!plan.value || !canEditPlan.value) return
    const next = removePlanGraphNode(plan.value, nodeId)
    if (!next) return
    editHistory.wrapEditableMutation(() => {
      plan.value = next
      if (selectedNodeId.value === nodeId) {
        selectedNodeId.value = businessNodeOrder(plan.value!)[0]?.id ?? FLOW_CONFIG_SELECTION
      }
    })
  }

  function replacePlan(next: WorkflowPlan) {
    if (!canEditPlan.value) return
    editHistory.wrapEditableMutation(() => {
      plan.value = next
    })
  }

  function autoLayoutCurrentPlan() {
    if (!plan.value || !canEditPlan.value) return
    editHistory.wrapEditableMutation(() => {
      plan.value = autoLayoutPlan(plan.value!)
    })
  }

  function updateSelectedNode(patch: Partial<import('../api/workflows').WorkflowPlanNode>) {
    if (!plan.value || !selectedNodeId.value || !canEditPlan.value) return
    editHistory.wrapEditableMutation(() => {
      plan.value = updateBusinessNode(plan.value!, selectedNodeId.value!, patch)
    })
  }

  async function validatePlan() {
    if (!plan.value || !selectedId.value) return
    validating.value = true
    validationIssues.value = []
    try {
      const issues = await runPublishValidation()
      validationIssues.value = issues
      if (issues.length === 0) {
        message.success('DAG 校验通过')
      } else {
        message.warning(`发现 ${issues.length} 个问题`)
      }
    } catch (e) {
      message.error(friendlyErrorMessage(e, '校验失败'))
      console.error(e)
    } finally {
      validating.value = false
    }
  }

  async function saveDraftSilent(): Promise<boolean> {
    if (!plan.value || !selectedId.value || !canEditPlan.value || !isDirty.value) return true
    try {
      await persistDraft()
      return true
    } catch (e) {
      console.error('[Workflows] auto-save failed', e)
      return false
    }
  }

  async function flushDraftBeforeLeave(): Promise<boolean> {
    if (!canEditPlan.value || !isDirty.value) return true
    saving.value = true
    try {
      return await saveDraftSilent()
    } finally {
      saving.value = false
    }
  }

  async function saveDraft() {
    if (!plan.value || !selectedId.value) return
    saving.value = true
    try {
      await persistDraft()
      validationIssues.value = []
      message.success('草稿已保存')
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存草稿失败'))
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function publish() {
    if (!plan.value || !selectedId.value) return
    saving.value = isDirty.value
    try {
      if (isDirty.value) {
        await persistDraft()
      }
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存草稿失败'))
      console.error(e)
      return
    } finally {
      saving.value = false
    }
    const issues = await runPublishValidation()
    if (issues.length > 0) {
      validationIssues.value = issues
      message.warning(issues[0])
      return
    }
    validationIssues.value = []
    publishing.value = true
    try {
      const ver = selectedVersion.value ?? editVersion.value
      const data = await publishWorkflow(selectedId.value, ver)
      await loadVersions(selectedId.value)
      applyEditable({
        workflowId: data.workflowId,
        version: data.version,
        status: 'published',
        plan: data.plan,
        catalog: data.catalog,
      })
      message.success('已发布')
      await refreshPage()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '发布失败'))
      console.error(e)
    } finally {
      publishing.value = false
    }
  }

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
        suppressVersionWatch = true
        selectedVersion.value = draft.version
        suppressVersionWatch = false
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

  function openTemplateModal() {
    if (!canEditPlan.value) return
    showTemplateModal.value = true
  }

  function bindImportInputRef(el: unknown) {
    importInputRef.value = el instanceof HTMLInputElement ? el : null
  }

  onBeforeRouteLeave(async () => {
    if (!(await flushDraftBeforeLeave())) {
      return false
    }
    return true
  })

  watch(() => route.params.workflowId, async (param) => {
    if (suppressRouteWatch) return
    await catalogReady
    const id = typeof param === 'string' ? param.trim() : ''
    if (!id) {
      if (workflows.value.length > 0) {
        await selectWorkflow(workflows.value[0].id)
      }
      return
    }
    if (id === selectedId.value && plan.value) return
    await selectWorkflow(id)
  })

  watch(nodeDefaults, (defaults) => {
    if (!defaults || !plan.value || !canEditPlan.value) return
    const next = applyPlanDefaults(plan.value, defaults)
    if (JSON.stringify(next) !== JSON.stringify(plan.value)) {
      editHistory.wrapEditableMutation(() => {
        plan.value = next
      })
    }
  })

  function isTextInputFocused(): boolean {
    const el = document.activeElement
    if (!el) return false
    if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return true
    return (el as HTMLElement).isContentEditable
  }

  function handleUndoRedoKeydown(e: KeyboardEvent) {
    if (!canEditPlan.value || !plan.value) return
    if (!(e.ctrlKey || e.metaKey)) return
    if (isTextInputFocused()) return
    const key = e.key.toLowerCase()
    if (key === 'z' && !e.shiftKey) {
      e.preventDefault()
      editHistory.undo()
      return
    }
    if (key === 'y' || (key === 'z' && e.shiftKey)) {
      e.preventDefault()
      editHistory.redo()
    }
  }

  onMounted(() => window.addEventListener('keydown', handleUndoRedoKeydown))
  onUnmounted(() => window.removeEventListener('keydown', handleUndoRedoKeydown))

  const catalogReady = loadCatalogOptions()

  void (async () => {
    await catalogReady
    await refreshPage()
  })()

  return reactive({
    loading,
    detailLoading,
    saving,
    publishing,
    validating,
    validationIssues,
    validationHighlightNodeIds,
    canTryInChat,
    canCompareVersions,
    workflows,
    versions,
    workflowSearch,
    selectedId,
    selectedVersion,
    selectedWorkflow,
    filteredWorkflows,
    plan,
    catalogExamples,
    catalogIntentAfter,
    definitionDisplayName,
    definitionDescription,
    isFlowConfigSelected,
    editStatus,
    editVersion,
    selectedNodeId,
    selectedNode,
    businessNodes,
    previewNodes,
    isParallelWorkflow,
    canApplyParallelTemplate,
    toolOptions,
    skillOptions,
    kbOptions,
    nodeDefaults,
    isDirty,
    canEditPlan,
    canUndo: editHistory.canUndo,
    canRedo: editHistory.canRedo,
    undo: editHistory.undo,
    redo: editHistory.redo,
    showSaveDraftButton,
    showPublishButton,
    workflowPhase,
    versionStatus,
    versionStatusLabel,
    versionStatusTagType,
    versionOptions,
    isWorkflowSwitchDisabled,
    showCreate,
    showEdit,
    showDeleteConfirm,
    showDeleteVersionConfirm,
    showTemplateModal,
    showImportModal,
    importPreviewLoading,
    importPreviewBody,
    importPreviewIssues,
    importMode,
    importDraft,
    canConfirmImport,
    createDraft,
    isDuplicateCreate,
    editForm,
    canConfirmCreate,
    cardMenuOptions,
    moreMenuOptions,
    refreshPage,
    selectWorkflow,
    addNode,
    applyParallelDualRagTemplate,
    applyWorkflowTemplate,
    addParallelBranch,
    openInChat,
    openVersionDiff,
    confirmImportPreview,
    closeImportModal,
    setImportMode,
    refreshImportValidation,
    exportWorkflowJson,
    openTemplateModal,
    removeNode,
    replacePlan,
    autoLayoutCurrentPlan,
    updateSelectedNode,
    validatePlan,
    saveDraft,
    publish,
    confirmCreate,
    confirmEdit,
    confirmDelete,
    confirmDeleteVersion,
    openCreateModal,
    closeCreateModal,
    openDuplicateAsNew,
    toggleEnabled,
    handleCardMenuSelect,
    handleMoreMenuSelect,
    bindImportInputRef,
    handleImportFile,
    triggerImport,
  })
}
