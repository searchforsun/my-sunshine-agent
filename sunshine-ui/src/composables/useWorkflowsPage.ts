import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch, type ComputedRef, type InjectionKey, type Ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import {
  fetchWorkflowNodeDefaults,
  getWorkflowEditable,
  getWorkflowVersion,
  listWorkflows,
  listWorkflowVersions,
  publishWorkflow,
  saveWorkflowDraft,
  updateWorkflow,
  validateWorkflowPlan,
  type WorkflowEditable,
  type WorkflowEntry,
  type WorkflowNodeDefaultsResponse,
  type WorkflowPlan,
  type WorkflowVersion,
} from '../api/workflows'
import { useWorkflowImportActions } from './useWorkflowImportActions'
import { useWorkflowLifecycleActions } from './useWorkflowLifecycleActions'
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
  buildExclusiveBranchRagPlan,
  businessNodeOrder,
  collectBusinessNodeValidationIssues,
  FLOW_CONFIG_SELECTION,
  insertBusinessNode,
  isParallelPlan,
  normalizeWorkflowPlan,
  reconcilePlanDataFlow,
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
  const showTemplateModal = ref(false)
  const savedSnapshot = ref('')
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
    const normalized = reconcilePlanDataFlow(
      applyPlanDefaults(normalizeWorkflowPlan(data.plan, wfId), nodeDefaults.value),
    )
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
    // 零延迟本地规则（画布即时反馈）；发布最终以服务端 WorkflowPlanValidator 为准
    return [
      ...validatePlanTopologyLocally(plan.value),
      ...collectBusinessNodeValidationIssues(plan.value),
      ...collectRetryValidationIssues(plan.value, null, true),
    ]
  }

  async function persistDraft(): Promise<void> {
    if (!plan.value || !selectedId.value) return
    const wfId = selectedId.value
    const normalized = reconcilePlanDataFlow(normalizeWorkflowPlan(plan.value, wfId))
    plan.value = normalized
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
    const normalized = reconcilePlanDataFlow(normalizeWorkflowPlan(plan.value, selectedId.value))
    plan.value = normalized
    // 服务端 WorkflowPlanValidator 为发布权威（与本地规则并存，非替代）
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
    if (!nodeDefaults.value) {
      message.warning('节点默认策略未加载，请刷新页面后重试')
      return
    }
    editHistory.wrapEditableMutation(() => {
      const defaults = nodeDefaults.value!
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
    if (!nodeDefaults.value) {
      message.warning('节点默认策略未加载，请刷新页面后重试')
      return
    }
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
      case 'exclusive-branch-rag':
        applyWorkflowTemplatePlan(
          buildExclusiveBranchRagPlan,
          '已应用条件分支检索模板（start → exclusive → RAG → answer）',
        )
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
    if (!nodeDefaults.value) {
      message.warning('节点默认策略未加载，请刷新页面后重试')
      return
    }
    editHistory.wrapEditableMutation(() => {
      const defaults = nodeDefaults.value!
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

  const importActions = useWorkflowImportActions({
    message,
    workflows,
    selectedId,
    nodeDefaults,
    definitionDisplayName,
    definitionDescription,
    refreshPage,
    selectWorkflow,
  })

  const lifecycle = useWorkflowLifecycleActions({
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
    setSuppressVersionWatch: (v) => { suppressVersionWatch = v },
    publish,
    openVersionDiff,
    triggerImport: () => importActions.triggerImport(),
  })

  function openTemplateModal() {
    if (!canEditPlan.value) return
    showTemplateModal.value = true
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
    showTemplateModal,
    ...importActions,
    ...lifecycle,
    refreshPage,
    selectWorkflow,
    addNode,
    applyParallelDualRagTemplate,
    applyWorkflowTemplate,
    addParallelBranch,
    openInChat,
    openVersionDiff,
    openTemplateModal,
    removeNode,
    replacePlan,
    autoLayoutCurrentPlan,
    updateSelectedNode,
    validatePlan,
    saveDraft,
    publish,
  })
}
