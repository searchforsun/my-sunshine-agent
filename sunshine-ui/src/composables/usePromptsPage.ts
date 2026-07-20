import { computed, h, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { NIcon, useMessage, type DropdownOption } from 'naive-ui'
import { CopyOutline } from '@vicons/ionicons5'
import {
  addPromptVersion,
  createPrompt,
  dryRunRouting,
  ensurePromptIdPrefix,
  getPrompt,
  listPrompts,
  listPromptVersions,
  parseRoutingContentJson,
  publishPrompt,
  rollbackPrompt,
  serializeRoutingContent,
  setPromptEnabled,
  shortPromptId,
  updatePrompt,
  validateRoutingRules,
  type PromptCreateBody,
  type PromptDetail,
  type PromptListItem,
  type PromptVersionItem,
  type RoutingDryRunResponse,
  type RoutingRuleContent,
  type RoutingWarningItem,
} from '../api/prompts'
import { listWorkflowCatalog, type WorkflowCatalogEntry } from '../api/workflows'
import { friendlyErrorMessage } from '../api/apiError'
import { formatSkillVersionTime } from '../utils/formatSkillVersionTime'

export const PROMPTS_PAGE_KEY = Symbol('promptsPage')

export type PromptsTab = 'all' | 'routing' | 'react'
export type CreateModalKind = 'routing' | 'react'
export type PromptVersionStatus = 'live' | 'inactive' | 'draft'

function resolvePromptVersionStatus(
  v: PromptVersionItem,
  activeNum: number | null,
): PromptVersionStatus {
  if (v.status === 'draft') return 'draft'
  if (v.version === activeNum) return 'live'
  return 'inactive'
}

function versionStatusLabel(status: PromptVersionStatus): string {
  if (status === 'live') return '生效'
  if (status === 'draft') return '草稿'
  return '非生效'
}

function versionStatusTagType(status: PromptVersionStatus): 'success' | 'warning' | 'default' {
  if (status === 'live') return 'success'
  if (status === 'draft') return 'warning'
  return 'default'
}

export function usePromptsPage() {
  const message = useMessage()
  const activeTab = ref<PromptsTab>('all')
  const loading = ref(false)
  const detailLoading = ref(false)
  const saving = ref(false)
  const publishing = ref(false)
  const rollingBack = ref(false)
  const forking = ref(false)
  const creating = ref(false)
  const validating = ref(false)
  const dryRunning = ref(false)
  /** 路由 Tab 右侧：规则编辑 / 独立试跑页 */
  const routingPane = ref<'editor' | 'dry-run'>('editor')

  const prompts = ref<PromptListItem[]>([])
  const workflowCatalog = ref<WorkflowCatalogEntry[]>([])
  const selectedId = ref<string | null>(null)
  const detail = ref<PromptDetail | null>(null)
  const versions = ref<PromptVersionItem[]>([])

  const editDisplayName = ref('')
  const editDescription = ref('')
  const editPriority = ref(0)
  const editContentText = ref('')
  const editContentJson = ref('')
  /** 内容区是否写入 contentJson（timeline 等 JSON 为主的条目） */
  const contentUsesJson = ref(false)
  const editChangeNote = ref('')
  /** 当前预览/操作的版本号（对齐 Skills selectedVersion） */
  const selectedVersion = ref<number | null>(null)

  const routingForm = ref<RoutingRuleContent>(parseRoutingContentJson(null))
  const routingWarnings = ref<RoutingWarningItem[]>([])
  const dryRunQuery = ref('')
  const dryRunResult = ref<RoutingDryRunResponse | null>(null)

  const showCreateModal = ref(false)
  const createModalKind = ref<CreateModalKind>('routing')
  const createDraft = ref({
    id: '',
    kind: 'routing-rule',
    displayName: '',
    description: '',
    priority: 0,
  })

  const filteredPrompts = computed(() => {
    const list = [...prompts.value]
    if (activeTab.value === 'routing') {
      return list
        .filter(p => p.kind === 'routing-rule')
        .sort((a, b) => b.priority - a.priority || a.id.localeCompare(b.id))
    }
    if (activeTab.value === 'react') {
      return list
        .filter(p => p.kind === 'react-prompt')
        .sort((a, b) => a.id.localeCompare(b.id))
    }
    // 全部 = 系统配置：排除 routing-rule / react-prompt
    return list
      .filter(p => p.kind !== 'routing-rule' && p.kind !== 'react-prompt')
      .sort((a, b) => {
        if (a.kind !== b.kind) return a.kind.localeCompare(b.kind)
        return a.id.localeCompare(b.id)
      })
  })

  const listPanelTitle = computed(() => {
    if (activeTab.value === 'routing') return '路由规则'
    if (activeTab.value === 'react') return 'React 提示词'
    return '系统配置'
  })

  const showListCreateButton = computed(
    () => activeTab.value === 'routing' || activeTab.value === 'react',
  )

  const listCreateButtonLabel = computed(() =>
    activeTab.value === 'routing' ? '新建规则' : '新建场景',
  )

  const selectedListItem = computed(() =>
    prompts.value.find(p => p.id === selectedId.value) ?? null,
  )

  const isRoutingSelected = computed(() => selectedListItem.value?.kind === 'routing-rule')

  const reactPromptOptions = computed(() =>
    prompts.value
      .filter(p => p.kind === 'react-prompt')
      .sort((a, b) => a.id.localeCompare(b.id))
      .map(p => {
        const shortId = shortPromptId(p.id)
        const name = p.displayName?.trim() || shortId
        const desc = (p.description ?? '').trim()
        return {
          label: desc ? `${name}｜${desc}` : name,
          value: p.id,
        }
      }),
  )

  const workflowOptions = computed(() =>
    workflowCatalog.value
      .slice()
      .sort((a, b) => a.id.localeCompare(b.id))
      .map(w => ({
        label: w.displayName && w.displayName !== w.id
          ? `${w.displayName}（${w.id}）`
          : w.id,
        value: w.id,
      })),
  )

  const selectedVersionEntry = computed(() => {
    if (selectedVersion.value == null) return null
    return versions.value.find(v => v.version === selectedVersion.value) ?? null
  })

  const hasDraft = computed(() => versions.value.some(v => v.status === 'draft'))

  const selectedVersionStatus = computed((): PromptVersionStatus | null => {
    const ver = selectedVersionEntry.value
    if (!ver || !detail.value) return null
    return resolvePromptVersionStatus(ver, detail.value.activeVersion)
  })

  const detailVersionTagType = computed(() => {
    const status = selectedVersionStatus.value
    return status ? versionStatusTagType(status) : 'default'
  })

  const selectedVersionStatusLabel = computed(() => {
    const status = selectedVersionStatus.value
    return status ? versionStatusLabel(status) : ''
  })

  /** 时间版本（对齐 Skills）：下拉仅显示创建时间，状态由 Tag 表达 */
  const versionOptions = computed(() =>
    versions.value.map(v => ({
      label: formatSkillVersionTime(v.createdAt),
      value: v.version,
    })),
  )

  const showVersionSelect = computed(() => versions.value.length > 0)

  /** 仅草稿可编辑（生效 / 非生效 published 只读） */
  const isContentEditable = computed(() => selectedVersionStatus.value === 'draft')

  /** 草稿 / 非生效已发布 → 显示主按钮 */
  const showPrimaryPublishButton = computed(() => {
    const status = selectedVersionStatus.value
    return status === 'draft' || status === 'inactive'
  })

  const primaryPublishLabel = computed(() =>
    selectedVersionStatus.value === 'draft' ? '发布并生效' : '设为此生效版',
  )

  const showForkToDraft = computed(() => {
    const status = selectedVersionStatus.value
    return (status === 'live' || status === 'inactive') && !hasDraft.value
  })

  const showSaveDraftButton = computed(() => selectedVersionStatus.value === 'draft')

  const isActionBusy = computed(
    () => saving.value || publishing.value || rollingBack.value || forking.value || creating.value,
  )

  const moreMenuOptions = computed((): DropdownOption[] => {
    const opts: DropdownOption[] = []
    if (showForkToDraft.value) {
      opts.push({
        label: '复制为草稿',
        key: 'fork',
        icon: () => h(NIcon, { component: CopyOutline, size: 14 }),
        disabled: forking.value,
      })
    }
    // 「发布并生效 / 设为此生效版」已在顶栏主按钮，勿在 ⋯ 菜单重复
    return opts
  })

  const showMoreMenu = computed(() => moreMenuOptions.value.length > 0)

  const createModalTitle = computed(() =>
    createModalKind.value === 'routing' ? '新建规则' : '新建场景',
  )

  const createIdPlaceholder = computed(() =>
    createModalKind.value === 'routing' ? 'structural-plan' : 'demo-scenario',
  )

  async function refreshList(keepSelection = true) {
    loading.value = true
    try {
      const [promptList, catalog] = await Promise.all([
        listPrompts(),
        listWorkflowCatalog().catch(() => [] as WorkflowCatalogEntry[]),
      ])
      prompts.value = promptList
      workflowCatalog.value = catalog
      const visible = filteredPrompts.value
      if (!keepSelection || !selectedId.value || !visible.some(p => p.id === selectedId.value)) {
        selectedId.value = visible[0]?.id ?? null
      }
      if (selectedId.value) {
        await loadDetail(selectedId.value)
      } else {
        detail.value = null
        versions.value = []
        selectedVersion.value = null
      }
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载提示词列表失败'))
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  async function loadDetail(id: string) {
    detailLoading.value = true
    try {
      const [d, vers] = await Promise.all([getPrompt(id), listPromptVersions(id)])
      detail.value = d
      versions.value = vers
      const draft = vers.find(v => v.status === 'draft')
      const active = vers.find(v => v.version === d.activeVersion)
      const pick = draft ?? active ?? vers[0] ?? null
      selectedVersion.value = pick?.version ?? null
      if (pick) {
        loadVersionIntoEditor(pick)
        editDisplayName.value = d.displayName
        editDescription.value = d.description ?? ''
        editPriority.value = d.priority
      } else {
        applyDetailToEditors(d)
      }
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载详情失败'))
      console.error(e)
    } finally {
      detailLoading.value = false
    }
  }

  function applyDetailToEditors(d: PromptDetail) {
    editDisplayName.value = d.displayName
    editDescription.value = d.description ?? ''
    editPriority.value = d.priority
    const content = d.activeVersionContent
    applyVersionContent(content?.contentText ?? null, content?.contentJson ?? null, d.kind)
    editChangeNote.value = ''
    if (d.kind === 'routing-rule') {
      routingForm.value = parseRoutingContentJson(content?.contentJson)
      routingWarnings.value = []
    }
  }

  function applyVersionContent(contentText: string | null, contentJson: string | null, kind?: string) {
    const preferJson = kind === 'timeline' || kind === 'routing-rule'
      || (!contentText?.trim() && !!contentJson?.trim())
    contentUsesJson.value = preferJson && kind !== 'routing-rule'
    if (kind === 'routing-rule') {
      editContentText.value = ''
      editContentJson.value = contentJson ?? ''
      contentUsesJson.value = true
      return
    }
    if (contentUsesJson.value) {
      editContentText.value = contentJson ?? ''
      editContentJson.value = contentJson ?? ''
    } else {
      editContentText.value = contentText ?? ''
      editContentJson.value = contentJson ?? ''
    }
  }

  async function selectPrompt(id: string) {
    routingPane.value = 'editor'
    if (id === selectedId.value) return
    selectedId.value = id
    await loadDetail(id)
  }

  function openRoutingDryRun() {
    routingPane.value = 'dry-run'
  }

  function onVersionSelected(ver: number | null) {
    if (ver == null) return
    const entry = versions.value.find(v => v.version === ver)
    if (entry) loadVersionIntoEditor(entry)
  }

  async function saveMeta() {
    if (!selectedId.value || !detail.value) return
    if (!isContentEditable.value) {
      message.warning('请先复制为草稿后再修改')
      return
    }
    if (detail.value.kind === 'react-prompt' && !editDescription.value.trim()) {
      message.warning('请填写场景描述（写清适用问法，便于路由绑定命中）')
      return
    }
    saving.value = true
    try {
      await updatePrompt(selectedId.value, {
        displayName: editDisplayName.value.trim(),
        description: editDescription.value.trim(),
        priority: editPriority.value,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      message.success('元数据已保存')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存元数据失败'))
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function saveVersion(status: 'draft' | 'published' = 'draft') {
    if (!selectedId.value || !detail.value) return
    if (status === 'draft' && !isContentEditable.value) {
      message.warning('生效版本不可直接修改，请先「复制为草稿」')
      return
    }
    if (detail.value.kind === 'react-prompt' && !editDescription.value.trim()) {
      message.warning('请填写场景描述（写清适用问法，便于路由绑定命中）')
      return
    }
    const raw = editContentText.value
    if (!raw.trim() && !editContentJson.value.trim()) {
      message.warning('请填写内容')
      return
    }
    const contentText = contentUsesJson.value ? null : raw
    const contentJson = contentUsesJson.value
      ? (raw.trim() || null)
      : (editContentJson.value.trim() || null)
    saving.value = true
    try {
      await updatePrompt(selectedId.value, {
        displayName: editDisplayName.value.trim() || detail.value.displayName,
        description: editDescription.value.trim(),
        priority: editPriority.value,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      const latest = await getPrompt(selectedId.value)
      await addPromptVersion(selectedId.value, {
        status,
        contentText,
        contentJson,
        expectedUpdatedAt: latest.updatedAt ?? null,
      })
      message.success(status === 'published' ? '已保存并发布' : '草稿已保存')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存版本失败'))
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function saveRoutingRule() {
    if (!selectedId.value || !detail.value) return
    if (!isContentEditable.value) {
      message.warning('生效版本不可直接修改，请先「复制为草稿」')
      return
    }
    const contentJson = serializeRoutingContent(routingForm.value)
    saving.value = true
    validating.value = true
    try {
      const validateRes = await validateRoutingRules([{
        id: selectedId.value,
        priority: editPriority.value,
        enabled: detail.value.enabled,
        contentJson,
      }])
      routingWarnings.value = validateRes.warnings ?? []
      await updatePrompt(selectedId.value, {
        displayName: editDisplayName.value.trim(),
        description: editDescription.value.trim(),
        priority: editPriority.value,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      const latest = await getPrompt(selectedId.value)
      await addPromptVersion(selectedId.value, {
        status: 'draft',
        contentText: null,
        contentJson,
        expectedUpdatedAt: latest.updatedAt ?? null,
      })
      if (routingWarnings.value.length) {
        message.warning(`已保存草稿（${routingWarnings.value.length} 条冲突警告）`)
      } else {
        message.success('路由规则草稿已保存')
      }
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存路由规则失败'))
      console.error(e)
    } finally {
      saving.value = false
      validating.value = false
    }
  }

  async function handlePublish(version?: number) {
    if (!selectedId.value || !detail.value) return
    const target = version ?? selectedVersion.value ?? undefined
    publishing.value = true
    try {
      await publishPrompt(selectedId.value, {
        version: target,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      message.success('已发布并生效')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '发布失败'))
      console.error(e)
    } finally {
      publishing.value = false
    }
  }

  async function handlePrimaryPublish() {
    if (!showPrimaryPublishButton.value || selectedVersion.value == null) return
    await handlePublish(selectedVersion.value)
  }

  async function forkToDraft() {
    if (!selectedId.value || !detail.value || !selectedVersionEntry.value) return
    if (hasDraft.value) {
      message.warning('已有草稿，请先发布或切换到草稿编辑')
      return
    }
    const ver = selectedVersionEntry.value
    forking.value = true
    try {
      await addPromptVersion(selectedId.value, {
        status: 'draft',
        contentText: ver.contentText,
        contentJson: ver.contentJson,
        changeNote: `从 v${ver.version} 复制为新草稿`,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      message.success('已复制为新草稿')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '复制草稿失败'))
      console.error(e)
    } finally {
      forking.value = false
    }
  }

  async function handleMoreMenuSelect(key: string | number) {
    if (key === 'fork') await forkToDraft()
  }

  async function handleRollback(version: number) {
    if (!selectedId.value || !detail.value) return
    rollingBack.value = true
    try {
      await rollbackPrompt(selectedId.value, version, detail.value.updatedAt ?? null)
      message.success(`已回滚到 v${version}`)
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '回滚失败'))
      console.error(e)
    } finally {
      rollingBack.value = false
    }
  }

  async function handleToggleEnabled(item: PromptListItem, enabled: boolean) {
    try {
      await setPromptEnabled(item.id, enabled)
      message.success(enabled ? '已启用' : '已停用')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '切换启用状态失败'))
      console.error(e)
    }
  }

  async function runDryRun() {
    const q = dryRunQuery.value.trim()
    if (!q) {
      message.warning('请输入试跑问句')
      return
    }
    dryRunning.value = true
    try {
      dryRunResult.value = await dryRunRouting(q)
    } catch (e) {
      message.error(friendlyErrorMessage(e, '试跑失败'))
      console.error(e)
    } finally {
      dryRunning.value = false
    }
  }

  function openCreateModal(tabKind: CreateModalKind) {
    createModalKind.value = tabKind
    if (tabKind === 'routing') {
      createDraft.value = {
        id: '',
        kind: 'routing-rule',
        displayName: '',
        description: '',
        priority: 10,
      }
    } else {
      createDraft.value = {
        id: '',
        kind: 'react-prompt',
        displayName: '',
        description: '',
        priority: 0,
      }
    }
    showCreateModal.value = true
  }

  async function handleCreate() {
    const rawId = createDraft.value.id.trim()
    const displayName = createDraft.value.displayName.trim()
    const description = createDraft.value.description.trim()
    const kind = createDraft.value.kind.trim()
    if (!rawId || !displayName || !kind) {
      message.warning('请填写 ID 与展示名')
      return
    }
    if (createModalKind.value === 'react' && !description) {
      message.warning('请填写场景描述（写清适用问法，便于路由绑定命中）')
      return
    }
    if (createModalKind.value === 'routing' && kind !== 'routing-rule') {
      message.warning('路由规则类型固定为 routing-rule')
      return
    }
    if (createModalKind.value === 'react' && kind !== 'react-prompt') {
      message.warning('场景类型固定为 react-prompt')
      return
    }
    const id = ensurePromptIdPrefix(rawId, kind)
    creating.value = true
    try {
      const body: PromptCreateBody = {
        id,
        kind,
        displayName,
        description,
        priority: createDraft.value.priority,
        enabled: false,
        status: 'draft',
        contentText: kind === 'routing-rule' ? undefined : '',
        contentJson: kind === 'routing-rule'
          ? serializeRoutingContent(parseRoutingContentJson(null))
          : undefined,
        changeNote: '新建',
      }
      await createPrompt(body)
      message.success('已创建')
      showCreateModal.value = false
      routingPane.value = 'editor'
      await refreshList(false)
      selectedId.value = id
      await loadDetail(id)
    } catch (e) {
      message.error(friendlyErrorMessage(e, '创建失败'))
      console.error(e)
    } finally {
      creating.value = false
    }
  }

  function loadVersionIntoEditor(ver: PromptVersionItem) {
    selectedVersion.value = ver.version
    applyVersionContent(ver.contentText, ver.contentJson, detail.value?.kind)
    editChangeNote.value = ''
    if (detail.value?.kind === 'routing-rule') {
      routingForm.value = parseRoutingContentJson(ver.contentJson)
    }
  }

  watch(activeTab, async () => {
    const visible = filteredPrompts.value
    if (!selectedId.value || !visible.some(p => p.id === selectedId.value)) {
      selectedId.value = visible[0]?.id ?? null
      if (selectedId.value) {
        await loadDetail(selectedId.value)
      } else {
        detail.value = null
        versions.value = []
        selectedVersion.value = null
      }
    }
    dryRunResult.value = null
    routingWarnings.value = []
    routingPane.value = 'editor'
  })

  return reactive({
    activeTab,
    loading,
    detailLoading,
    saving,
    publishing,
    rollingBack,
    forking,
    creating,
    validating,
    dryRunning,
    routingPane,
    prompts,
    filteredPrompts,
    listPanelTitle,
    showListCreateButton,
    listCreateButtonLabel,
    selectedId,
    selectedListItem,
    detail,
    versions,
    editDisplayName,
    editDescription,
    editPriority,
    editContentText,
    editContentJson,
    editChangeNote,
    selectedVersion,
    selectedVersionEntry,
    selectedVersionStatus,
    selectedVersionStatusLabel,
    detailVersionTagType,
    versionOptions,
    showVersionSelect,
    showPrimaryPublishButton,
    primaryPublishLabel,
    showForkToDraft,
    showSaveDraftButton,
    showMoreMenu,
    isContentEditable,
    isActionBusy,
    contentUsesJson,
    moreMenuOptions,
    hasDraft,
    isRoutingSelected,
    reactPromptOptions,
    workflowOptions,
    routingForm,
    routingWarnings,
    dryRunQuery,
    dryRunResult,
    showCreateModal,
    createModalKind,
    createModalTitle,
    createIdPlaceholder,
    createDraft,
    refreshList,
    selectPrompt,
    openRoutingDryRun,
    loadDetail,
    saveMeta,
    saveVersion,
    saveRoutingRule,
    handlePublish,
    handlePrimaryPublish,
    handleRollback,
    handleToggleEnabled,
    runDryRun,
    openCreateModal,
    handleCreate,
    loadVersionIntoEditor,
    onVersionSelected,
    forkToDraft,
    handleMoreMenuSelect,
  })
}

type UnwrapPageMember<T> =
  T extends Ref<infer V> ? V :
  T extends ComputedRef<infer V> ? V :
  T extends (...args: infer A) => infer R ? (...args: A) => R :
  T

type PromptsPageComposable = ReturnType<typeof usePromptsPage>

export type PromptsPageApi = {
  [K in keyof PromptsPageComposable]: UnwrapPageMember<PromptsPageComposable[K]>
}
