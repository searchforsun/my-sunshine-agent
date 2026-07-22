import { computed, ref, type Ref } from 'vue'
import {
  createPrompt,
  ensurePromptIdPrefix,
  listPrompts,
  parseRoutingContentJson,
  serializeRoutingContent,
  setPromptEnabled,
  type PromptCreateBody,
  type PromptDetail,
  type PromptListItem,
  type PromptVersionItem,
} from '../api/prompts'
import { listWorkflowCatalog, type WorkflowCatalogEntry } from '../api/workflows'
import { friendlyErrorMessage } from '../api/apiError'
import { tabForKind } from '../utils/prompts/promptVersionUtils'
import type { PromptsTab, usePromptsRouteState } from './usePromptsRouteState'

export type CreateModalKind = 'routing' | 'react'

export interface PromptListDeps {
  activeTab: Ref<PromptsTab>
  selectedId: Ref<string | null>
  routingPane: Ref<'editor' | 'dry-run'>
  systemPane: Ref<'editor' | 'principles'>
  routeState: ReturnType<typeof usePromptsRouteState>
  message: ReturnType<typeof import('naive-ui')['useMessage']>
  detail: Ref<PromptDetail | null>
  versions: Ref<PromptVersionItem[]>
  selectedVersion: Ref<number | null>
  creating: Ref<boolean>
  loadDetail: (id: string) => Promise<void>
}

export function usePromptList(deps: PromptListDeps) {
  const {
    activeTab,
    selectedId,
    routingPane,
    systemPane,
    routeState,
    message,
    detail,
    versions,
    selectedVersion,
    creating,
    loadDetail,
  } = deps

  const loading = ref(false)
  const promptSearch = ref('')
  const prompts = ref<PromptListItem[]>([])
  const workflowCatalog = ref<WorkflowCatalogEntry[]>([])

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
    let list = [...prompts.value]
    if (activeTab.value === 'routing') {
      list = list
        .filter(p => p.kind === 'routing-rule')
        .sort((a, b) => b.priority - a.priority || a.id.localeCompare(b.id))
    } else if (activeTab.value === 'react') {
      list = list
        .filter(p => p.kind === 'react-prompt')
        .sort((a, b) => a.id.localeCompare(b.id))
    } else {
      list = list
        .filter(p => p.kind !== 'routing-rule' && p.kind !== 'react-prompt')
        .sort((a, b) => {
          if (a.kind !== b.kind) return a.kind.localeCompare(b.kind)
          return a.id.localeCompare(b.id)
        })
    }
    const q = promptSearch.value.trim().toLowerCase()
    if (!q) return list
    return list.filter(
      p =>
        p.id.toLowerCase().includes(q)
        || (p.displayName ?? '').toLowerCase().includes(q),
    )
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
      const fromUrl = routeState.readId()
      const urlItem = fromUrl ? prompts.value.find(p => p.id === fromUrl) : null
      if (urlItem && !routeState.hasExplicitTab()) {
        activeTab.value = tabForKind(urlItem.kind)
      }
      const visible = filteredPrompts.value
      if (urlItem && visible.some(p => p.id === urlItem.id)) {
        selectedId.value = urlItem.id
      } else if (!keepSelection || !selectedId.value || !visible.some(p => p.id === selectedId.value)) {
        selectedId.value = visible[0]?.id ?? null
      }
      if (selectedId.value) {
        await loadDetail(selectedId.value)
      } else {
        detail.value = null
        versions.value = []
        selectedVersion.value = null
      }
      routeState.syncQuery({
        tab: activeTab.value,
        id: selectedId.value,
        pane: activeTab.value === 'routing' ? routingPane.value : 'editor',
        systemPane: activeTab.value === 'system' ? systemPane.value : 'editor',
      })
    } catch (e) {
      message.error(friendlyErrorMessage(e, '加载提示词列表失败'))
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  async function selectPrompt(id: string) {
    routingPane.value = 'editor'
    systemPane.value = 'editor'
    routeState.syncQuery({ id, pane: 'editor', systemPane: 'editor' })
    if (id === selectedId.value) return
    selectedId.value = id
    await loadDetail(id)
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

  return {
    loading,
    prompts,
    promptSearch,
    workflowCatalog,
    creating,
    filteredPrompts,
    listPanelTitle,
    showListCreateButton,
    listCreateButtonLabel,
    selectedListItem,
    showCreateModal,
    createModalKind,
    createModalTitle,
    createIdPlaceholder,
    createDraft,
    refreshList,
    selectPrompt,
    handleToggleEnabled,
    openCreateModal,
    handleCreate,
  }
}
