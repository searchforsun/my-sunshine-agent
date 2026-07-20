import { computed, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useMessage } from 'naive-ui'
import {
  addPromptVersion,
  createPrompt,
  dryRunRouting,
  getPrompt,
  listPrompts,
  listPromptVersions,
  parseFragmentMeta,
  parseRoutingContentJson,
  publishPrompt,
  rollbackPrompt,
  serializeFragmentMeta,
  serializeRoutingContent,
  setPromptEnabled,
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
import { friendlyErrorMessage } from '../api/apiError'

export const PROMPTS_PAGE_KEY = Symbol('promptsPage')

export type PromptsTab = 'all' | 'routing' | 'react'

const REACT_OVERLAY_IDS = ['mode-overlay.react', 'mode-overlay.react-restart'] as const

export function usePromptsPage() {
  const message = useMessage()
  const activeTab = ref<PromptsTab>('all')
  const loading = ref(false)
  const detailLoading = ref(false)
  const saving = ref(false)
  const publishing = ref(false)
  const rollingBack = ref(false)
  const creating = ref(false)
  const validating = ref(false)
  const dryRunning = ref(false)

  const prompts = ref<PromptListItem[]>([])
  const selectedId = ref<string | null>(null)
  const detail = ref<PromptDetail | null>(null)
  const versions = ref<PromptVersionItem[]>([])

  const editDisplayName = ref('')
  const editDescription = ref('')
  const editPriority = ref(0)
  const editContentText = ref('')
  const editContentJson = ref('')
  const editChangeNote = ref('')
  const previewVersion = ref<number | null>(null)

  const routingForm = ref<RoutingRuleContent>(parseRoutingContentJson(null))
  const routingWarnings = ref<RoutingWarningItem[]>([])
  const dryRunQuery = ref('')
  const dryRunResult = ref<RoutingDryRunResponse | null>(null)

  const reactOverlayText = ref<Record<string, string>>({
    'mode-overlay.react': '',
    'mode-overlay.react-restart': '',
  })
  const reactFragments = ref<Array<{
    id: string
    displayName: string
    enabled: boolean
    contentText: string
    attachTo: string
    sortOrder: number
  }>>([])

  const showCreateModal = ref(false)
  const createDraft = ref({
    id: '',
    kind: 'system',
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
        .filter(p => isReactComposeEntry(p))
        .sort((a, b) => {
          const ak = reactSortKey(a)
          const bk = reactSortKey(b)
          if (ak !== bk) return ak - bk
          return a.id.localeCompare(b.id)
        })
    }
    return list.sort((a, b) => {
      if (a.kind !== b.kind) return a.kind.localeCompare(b.kind)
      return a.id.localeCompare(b.id)
    })
  })

  const selectedListItem = computed(() =>
    prompts.value.find(p => p.id === selectedId.value) ?? null,
  )

  const isRoutingSelected = computed(() => selectedListItem.value?.kind === 'routing-rule')

  const activeVersionEntry = computed(() => {
    if (!detail.value) return null
    return versions.value.find(v => v.version === detail.value!.activeVersion) ?? null
  })

  const previewVersionEntry = computed(() => {
    if (previewVersion.value == null) return null
    return versions.value.find(v => v.version === previewVersion.value) ?? null
  })

  const hasDraft = computed(() => versions.value.some(v => v.status === 'draft'))

  function isReactComposeEntry(p: PromptListItem): boolean {
    if (p.kind === 'react-fragment') return true
    if (p.kind === 'mode-overlay') {
      return p.id === 'mode-overlay.react' || p.id === 'mode-overlay.react-restart'
        || p.id.startsWith('mode-overlay.react')
    }
    return false
  }

  function reactSortKey(p: PromptListItem): number {
    if (p.id === 'mode-overlay.react') return 0
    if (p.id === 'mode-overlay.react-restart') return 1
    if (p.kind === 'react-fragment') return 10 + p.priority
    return 20
  }

  async function refreshList(keepSelection = true) {
    loading.value = true
    try {
      prompts.value = await listPrompts()
      const visible = filteredPrompts.value
      if (!keepSelection || !selectedId.value || !visible.some(p => p.id === selectedId.value)) {
        selectedId.value = visible[0]?.id ?? null
      }
      if (selectedId.value) {
        await loadDetail(selectedId.value)
      } else {
        detail.value = null
        versions.value = []
      }
      if (activeTab.value === 'react') {
        await loadReactCompose()
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
      previewVersion.value = d.activeVersion
      applyDetailToEditors(d)
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
    editContentText.value = content?.contentText ?? ''
    editContentJson.value = content?.contentJson ?? ''
    editChangeNote.value = ''
    if (d.kind === 'routing-rule') {
      routingForm.value = parseRoutingContentJson(content?.contentJson)
      routingWarnings.value = []
    }
  }

  async function selectPrompt(id: string) {
    if (id === selectedId.value) return
    selectedId.value = id
    await loadDetail(id)
  }

  async function loadReactCompose() {
    const overlayTexts: Record<string, string> = {
      'mode-overlay.react': '',
      'mode-overlay.react-restart': '',
    }
    for (const id of REACT_OVERLAY_IDS) {
      const item = prompts.value.find(p => p.id === id)
      if (!item) continue
      try {
        const d = await getPrompt(id)
        overlayTexts[id] = d.activeVersionContent?.contentText ?? ''
      } catch (e) {
        console.error(e)
      }
    }
    reactOverlayText.value = overlayTexts

    const fragments = prompts.value.filter(p => p.kind === 'react-fragment')
    const loaded: typeof reactFragments.value = []
    for (const f of fragments) {
      try {
        const d = await getPrompt(f.id)
        const meta = parseFragmentMeta(d.activeVersionContent?.contentJson)
        loaded.push({
          id: f.id,
          displayName: f.displayName,
          enabled: f.enabled,
          contentText: d.activeVersionContent?.contentText ?? '',
          attachTo: meta.attachTo,
          sortOrder: meta.sortOrder,
        })
      } catch (e) {
        console.error(e)
      }
    }
    loaded.sort((a, b) => a.sortOrder - b.sortOrder || a.id.localeCompare(b.id))
    reactFragments.value = loaded
  }

  async function saveMeta() {
    if (!selectedId.value || !detail.value) return
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
    const text = editContentText.value
    const json = editContentJson.value
    if (!text.trim() && !json.trim()) {
      message.warning('contentText 与 contentJson 至少填一项')
      return
    }
    saving.value = true
    try {
      await addPromptVersion(selectedId.value, {
        status,
        contentText: text,
        contentJson: json.trim() ? json : null,
        changeNote: editChangeNote.value.trim() || undefined,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
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
        changeNote: editChangeNote.value.trim() || '更新路由规则',
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
    publishing.value = true
    try {
      await publishPrompt(selectedId.value, {
        version,
        expectedUpdatedAt: detail.value.updatedAt ?? null,
      })
      message.success('已发布')
      await refreshList()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '发布失败'))
      console.error(e)
    } finally {
      publishing.value = false
    }
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
      if (activeTab.value === 'react') {
        await loadReactCompose()
      }
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

  async function saveReactOverlay(id: string) {
    const item = prompts.value.find(p => p.id === id)
    if (!item) {
      message.warning(`未找到 ${id}`)
      return
    }
    saving.value = true
    try {
      const d = await getPrompt(id)
      await addPromptVersion(id, {
        status: 'draft',
        contentText: reactOverlayText.value[id] ?? '',
        contentJson: null,
        changeNote: 'ReAct 拼装编辑',
        expectedUpdatedAt: d.updatedAt ?? null,
      })
      message.success(`${id} 草稿已保存`)
      await refreshList()
      await loadReactCompose()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存失败'))
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function saveReactFragment(fragmentId: string) {
    const frag = reactFragments.value.find(f => f.id === fragmentId)
    if (!frag) return
    saving.value = true
    try {
      const d = await getPrompt(fragmentId)
      await updatePrompt(fragmentId, {
        displayName: frag.displayName,
        expectedUpdatedAt: d.updatedAt ?? null,
      })
      const latest = await getPrompt(fragmentId)
      await addPromptVersion(fragmentId, {
        status: 'draft',
        contentText: frag.contentText,
        contentJson: serializeFragmentMeta(frag.attachTo, frag.sortOrder),
        changeNote: 'ReAct fragment 编辑',
        expectedUpdatedAt: latest.updatedAt ?? null,
      })
      message.success(`${fragmentId} 草稿已保存`)
      await refreshList()
      await loadReactCompose()
    } catch (e) {
      message.error(friendlyErrorMessage(e, '保存 fragment 失败'))
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  function openCreateModal() {
    createDraft.value = {
      id: '',
      kind: activeTab.value === 'routing' ? 'routing-rule' : 'system',
      displayName: '',
      description: '',
      priority: activeTab.value === 'routing' ? 10 : 0,
    }
    showCreateModal.value = true
  }

  async function handleCreate() {
    const id = createDraft.value.id.trim()
    const displayName = createDraft.value.displayName.trim()
    const kind = createDraft.value.kind.trim()
    if (!id || !displayName || !kind) {
      message.warning('请填写 ID、类型与展示名')
      return
    }
    creating.value = true
    try {
      const body: PromptCreateBody = {
        id,
        kind,
        displayName,
        description: createDraft.value.description.trim(),
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
    previewVersion.value = ver.version
    editContentText.value = ver.contentText ?? ''
    editContentJson.value = ver.contentJson ?? ''
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
      }
    }
    if (activeTab.value === 'react') {
      await loadReactCompose()
    }
    dryRunResult.value = null
    routingWarnings.value = []
  })

  return reactive({
    activeTab,
    loading,
    detailLoading,
    saving,
    publishing,
    rollingBack,
    creating,
    validating,
    dryRunning,
    prompts,
    filteredPrompts,
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
    previewVersion,
    previewVersionEntry,
    activeVersionEntry,
    hasDraft,
    isRoutingSelected,
    routingForm,
    routingWarnings,
    dryRunQuery,
    dryRunResult,
    reactOverlayText,
    reactFragments,
    showCreateModal,
    createDraft,
    refreshList,
    selectPrompt,
    loadDetail,
    saveMeta,
    saveVersion,
    saveRoutingRule,
    handlePublish,
    handleRollback,
    handleToggleEnabled,
    runDryRun,
    saveReactOverlay,
    saveReactFragment,
    openCreateModal,
    handleCreate,
    loadVersionIntoEditor,
    loadReactCompose,
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
