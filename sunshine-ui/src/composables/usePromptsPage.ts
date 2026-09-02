import { reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import {
  usePromptsRouteState,
  type PromptsTab,
} from './usePromptsRouteState'
import { usePromptList, type CreateModalKind } from './usePromptList'
import { usePromptVersionOps } from './usePromptVersionOps'
import { useRoutingRuleOps } from './useRoutingRuleOps'
import { useRoutingDryRun } from './useRoutingDryRun'

export const PROMPTS_PAGE_KEY = Symbol('promptsPage')

export type { PromptsTab, CreateModalKind }
export type { PromptVersionStatus } from '../utils/prompts/promptVersionUtils'

export function usePromptsPage() {
  const message = useMessage()
  const route = useRoute()
  const routeState = usePromptsRouteState()
  const activeTab = ref<PromptsTab>(routeState.readTab())
  const selectedId = ref<string | null>(routeState.readId())
  const creating = ref(false)
  /** 路由 Tab 右侧：规则编辑 / 独立试跑页 */
  const routingPane = ref<'editor' | 'dry-run'>(routeState.readPane())
  /** 系统配置 Tab 右侧：编辑 / 原理分析（全局说明） */
  const systemPane = ref<'editor' | 'principles'>(routeState.readSystemPane())

  let refreshList!: (keepSelection?: boolean) => Promise<void>
  const routingContentBridge = {
    apply: (_contentJson: string | null) => {},
  }

  const versionOps = usePromptVersionOps({
    selectedId,
    message,
    refreshList: (keepSelection) => refreshList(keepSelection),
    applyRoutingContent: (contentJson) => routingContentBridge.apply(contentJson),
    creating,
  })

  const listOps = usePromptList({
    activeTab,
    selectedId,
    routingPane,
    systemPane,
    routeState,
    message,
    detail: versionOps.detail,
    versions: versionOps.versions,
    selectedVersion: versionOps.selectedVersion,
    creating,
    loadDetail: versionOps.loadDetail,
  })

  refreshList = listOps.refreshList

  const routingOps = useRoutingRuleOps({
    selectedId,
    detail: versionOps.detail,
    prompts: listOps.prompts,
    workflowCatalog: listOps.workflowCatalog,
    editDisplayName: versionOps.editDisplayName,
    editDescription: versionOps.editDescription,
    editPriority: versionOps.editPriority,
    saving: versionOps.saving,
    isContentEditable: versionOps.isContentEditable,
    selectedListItem: listOps.selectedListItem,
    message,
    refreshList,
  })

  routingContentBridge.apply = routingOps.applyRoutingContent

  const dryRunOps = useRoutingDryRun({
    routingPane,
    routeState,
    message,
  })

  function openPrinciples() {
    systemPane.value = 'principles'
    routeState.syncQuery({ tab: 'system', systemPane: 'principles' })
  }

  function closePrinciples() {
    systemPane.value = 'editor'
    routeState.syncQuery({ systemPane: 'editor' })
  }

  watch(activeTab, async (tab) => {
    const visible = listOps.filteredPrompts.value
    if (!selectedId.value || !visible.some(p => p.id === selectedId.value)) {
      selectedId.value = visible[0]?.id ?? null
      if (selectedId.value) {
        await versionOps.loadDetail(selectedId.value)
      } else {
        versionOps.detail.value = null
        versionOps.versions.value = []
        versionOps.selectedVersion.value = null
      }
    }
    dryRunOps.dryRunResult.value = null
    routingOps.routingWarnings.value = []
    if (tab !== 'routing') {
      routingPane.value = 'editor'
    }
    if (tab !== 'system') {
      systemPane.value = 'editor'
    }
    routeState.syncQuery({
      tab,
      id: selectedId.value,
      pane: tab === 'routing' ? routingPane.value : 'editor',
      systemPane: tab === 'system' ? systemPane.value : 'editor',
    })
  })

  watch(
    () => [route.query.tab, route.query.id, route.query.pane, route.query.view] as const,
    async () => {
      const tab = routeState.readTab()
      const id = routeState.readId()
      const pane = routeState.readPane()
      const sysPane = routeState.readSystemPane()
      if (activeTab.value !== tab) {
        activeTab.value = tab
      }
      if (tab === 'routing' && routingPane.value !== pane) {
        routingPane.value = pane
      }
      if (tab === 'system' && systemPane.value !== sysPane) {
        systemPane.value = sysPane
      }
      if (id && id !== selectedId.value && listOps.prompts.value.some(p => p.id === id)) {
        selectedId.value = id
        await versionOps.loadDetail(id)
      }
    },
  )

  return reactive({
    activeTab,
    loading: listOps.loading,
    detailLoading: versionOps.detailLoading,
    saving: versionOps.saving,
    publishing: versionOps.publishing,
    rollingBack: versionOps.rollingBack,
    forking: versionOps.forking,
    creating,
    validating: routingOps.validating,
    dryRunning: dryRunOps.dryRunning,
    routingPane,
    systemPane,
    prompts: listOps.prompts,
    filteredPrompts: listOps.filteredPrompts,
    promptSearch: listOps.promptSearch,
    listPanelTitle: listOps.listPanelTitle,
    showListCreateButton: listOps.showListCreateButton,
    listCreateButtonLabel: listOps.listCreateButtonLabel,
    selectedId,
    selectedListItem: listOps.selectedListItem,
    detail: versionOps.detail,
    versions: versionOps.versions,
    editDisplayName: versionOps.editDisplayName,
    editDescription: versionOps.editDescription,
    editPriority: versionOps.editPriority,
    editContentText: versionOps.editContentText,
    editContentJson: versionOps.editContentJson,
    editChangeNote: versionOps.editChangeNote,
    selectedVersion: versionOps.selectedVersion,
    selectedVersionEntry: versionOps.selectedVersionEntry,
    selectedVersionStatus: versionOps.selectedVersionStatus,
    selectedVersionStatusLabel: versionOps.selectedVersionStatusLabel,
    detailVersionTagType: versionOps.detailVersionTagType,
    versionOptions: versionOps.versionOptions,
    showVersionSelect: versionOps.showVersionSelect,
    showPrimaryPublishButton: versionOps.showPrimaryPublishButton,
    primaryPublishLabel: versionOps.primaryPublishLabel,
    showForkToDraft: versionOps.showForkToDraft,
    showSaveDraftButton: versionOps.showSaveDraftButton,
    showMoreMenu: versionOps.showMoreMenu,
    isContentEditable: versionOps.isContentEditable,
    isActionBusy: versionOps.isActionBusy,
    contentUsesJson: versionOps.contentUsesJson,
    moreMenuOptions: versionOps.moreMenuOptions,
    hasDraft: versionOps.hasDraft,
    isRoutingSelected: routingOps.isRoutingSelected,
    workflowOptions: routingOps.workflowOptions,
    routingForm: routingOps.routingForm,
    routingWarnings: routingOps.routingWarnings,
    dryRunQuery: dryRunOps.dryRunQuery,
    dryRunMode: dryRunOps.dryRunMode,
    dryRunResult: dryRunOps.dryRunResult,
    showCreateModal: listOps.showCreateModal,
    createModalKind: listOps.createModalKind,
    createModalTitle: listOps.createModalTitle,
    createIdPlaceholder: listOps.createIdPlaceholder,
    createDraft: listOps.createDraft,
    refreshList,
    selectPrompt: listOps.selectPrompt,
    openRoutingDryRun: dryRunOps.openRoutingDryRun,
    openPrinciples,
    closePrinciples,
    loadDetail: versionOps.loadDetail,
    saveMeta: versionOps.saveMeta,
    saveVersion: versionOps.saveVersion,
    saveRoutingRule: routingOps.saveRoutingRule,
    handlePublish: versionOps.handlePublish,
    handlePrimaryPublish: versionOps.handlePrimaryPublish,
    handleRollback: versionOps.handleRollback,
    handleToggleEnabled: listOps.handleToggleEnabled,
    runDryRun: dryRunOps.runDryRun,
    openCreateModal: listOps.openCreateModal,
    handleCreate: listOps.handleCreate,
    loadVersionIntoEditor: versionOps.loadVersionIntoEditor,
    onVersionSelected: versionOps.onVersionSelected,
    forkToDraft: versionOps.forkToDraft,
    handleMoreMenuSelect: versionOps.handleMoreMenuSelect,
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
