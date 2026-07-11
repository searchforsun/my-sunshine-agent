import { computed, onMounted, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  useMessage,
  type FormInst,
  type FormRules,
} from 'naive-ui'
import {
  filterCatalogBySource,
  listMcpServers,
  listSdkApplications,
  listToolCatalog,
  buildToolEnabledMap,
  patchTool,
  syncSdkApplication,
  type McpServer,
  type SdkApplication,
  type ToolCatalogEntry,
} from '../api/tools'
import type { TenantId } from '../api/tenants'
import { useToolsRouteState, type ToolsTab } from './useToolsRouteState'
import { useMcpServerActions } from './useMcpServerActions'
import { createToolCatalogColumns } from '../utils/tools/toolCatalogColumns'

export const TOOLS_PAGE_KEY = Symbol('toolsPage')

type TabKey = ToolsTab

const toolConfigRules: FormRules = {
  description: [
    {
      validator: (_rule, value: string) => {
        if (!(value ?? '').trim()) return new Error('请填写工具描述')
        return true
      },
      trigger: ['blur', 'input'],
    },
  ],
}

export function useToolsPage() {
  const message = useMessage()
  const route = useRoute()
  const routeState = useToolsRouteState()
  const activeTab = ref<TabKey>(routeState.readTab())
  const loading = ref(false)
  const saving = ref(false)
  const syncing = ref(false)
  const probing = ref(false)
  const importing = ref(false)

  const sdkApps = ref<SdkApplication[]>([])
  const mcpServers = ref<McpServer[]>([])
  const catalog = ref<ToolCatalogEntry[]>([])
  const enabledMap = ref<Map<string, boolean>>(new Map())

  const selectedSdkId = ref<string | null>(null)
  const selectedMcpId = ref<string | null>(null)

  const showToolEditModal = ref(false)
  const showToolSchemaModal = ref(false)
  const schemaViewTool = ref<ToolCatalogEntry | null>(null)
  const editingTool = ref<ToolCatalogEntry | null>(null)
  const toolConfigFormRef = ref<FormInst | null>(null)
  const toolConfigModel = ref({
    description: '',
    timelineSummaryTemplate: '',
    timelineSummaryExtract: '',
  })

  function bindToolConfigFormRef(el: unknown) {
    toolConfigFormRef.value = el as FormInst | null
  }

  const selectedSdk = computed(() =>
    sdkApps.value.find(a => a.id === selectedSdkId.value) ?? null,
  )

  const selectedMcp = computed(() =>
    mcpServers.value.find(s => s.id === selectedMcpId.value) ?? null,
  )

  const sdkTools = computed(() => {
    if (!selectedSdkId.value) return []
    return filterCatalogBySource(catalog.value, 'sdk', selectedSdkId.value)
  })

  const mcpTools = computed(() => {
    if (!selectedMcpId.value) return []
    return filterCatalogBySource(catalog.value, 'mcp', selectedMcpId.value)
  })

  const enabledToolCount = computed(() =>
    catalog.value.filter(t => enabledMap.value.get(t.id) === true).length,
  )

  async function refreshCatalog(tenantId: TenantId = 'default') {
    const all = await listToolCatalog(tenantId, false)
    catalog.value = all
    enabledMap.value = buildToolEnabledMap(all)
  }

  const mcpActions = useMcpServerActions({
    message,
    mcpServers,
    selectedMcpId,
    selectedMcp,
    saving,
    probing,
    importing,
    refreshMcp: async () => refreshMcp(),
    refreshCatalog: () => refreshCatalog(),
  })

  async function refreshMcp() {
    loading.value = true
    try {
      const [servers] = await Promise.all([
        listMcpServers(),
        refreshCatalog(),
      ])
      mcpServers.value = servers
      const fromQuery = routeState.readMcpId()
      if (fromQuery && servers.some(s => s.id === fromQuery)) {
        selectedMcpId.value = fromQuery
      } else if (selectedMcpId.value && !servers.some(s => s.id === selectedMcpId.value)) {
        selectedMcpId.value = null
      }
      if (!selectedMcpId.value && servers.length > 0) {
        selectedMcpId.value = servers[0].id
      }
      if (!mcpActions.mcpDetailEditing.value) {
        mcpActions.syncMcpDetailDraft()
      }
    } catch (e) {
      message.error('加载 MCP 服务失败')
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  async function handleToggleConfirmation(tool: ToolCatalogEntry, requireConfirmation: boolean) {
    try {
      await patchTool(tool.id, { requireConfirmation })
      const row = catalog.value.find(t => t.id === tool.id)
      if (row) row.requireConfirmation = requireConfirmation
      message.success(requireConfirmation ? '已开启人工确认' : '已关闭人工确认')
    } catch (e) {
      message.error('更新人工确认失败')
      console.error(e)
      await refreshCatalog()
    }
  }

  function statusTagType(status: string): 'success' | 'warning' | 'default' {
    if (status === 'online') return 'success'
    if (status === 'offline') return 'warning'
    return 'default'
  }

  function mcpStatusDotClass(server: McpServer): 'online' | 'offline' {
    return server.enabled && server.probeStatus === 'ok' ? 'online' : 'offline'
  }

  function mcpStatusTitle(server: McpServer): string {
    if (!server.enabled) return '已停用'
    if (server.probeStatus === 'ok') return '运行中'
    if (server.probeStatus === 'error') return '探测失败'
    return '待探测'
  }

  function mcpAvailableToolCount(serverId: string): number {
    return filterCatalogBySource(catalog.value, 'mcp', serverId)
      .filter(tool => enabledMap.value.get(tool.id) === true)
      .length
  }

  function mcpTotalToolCount(serverId: string): number {
    return filterCatalogBySource(catalog.value, 'mcp', serverId).length
  }

  async function refreshSdk() {
    loading.value = true
    try {
      const [apps] = await Promise.all([
        listSdkApplications(),
        refreshCatalog(),
      ])
      sdkApps.value = apps
      const fromQuery = routeState.readSdkId()
      if (fromQuery && apps.some(a => a.id === fromQuery)) {
        selectedSdkId.value = fromQuery
      } else if (selectedSdkId.value && !apps.some(a => a.id === selectedSdkId.value)) {
        selectedSdkId.value = null
      }
      if (!selectedSdkId.value && apps.length > 0) {
        selectedSdkId.value = apps[0].id
      }
    } catch (e) {
      message.error('加载 SDK 应用失败')
      console.error(e)
    } finally {
      loading.value = false
    }
  }

  async function refreshCurrentTab() {
    if (activeTab.value === 'sdk') await refreshSdk()
    else if (activeTab.value === 'mcp') await refreshMcp()
  }

  async function handleSyncSdk() {
    if (!selectedSdkId.value) return
    syncing.value = true
    try {
      await syncSdkApplication(selectedSdkId.value)
      message.success('同步完成')
      await refreshSdk()
    } catch (e) {
      message.error('同步失败')
      console.error(e)
    } finally {
      syncing.value = false
    }
  }

  async function handleToggleTool(tool: ToolCatalogEntry, enabled: boolean) {
    try {
      await patchTool(tool.id, { enabled })
      enabledMap.value.set(tool.id, enabled)
      const row = catalog.value.find(t => t.id === tool.id)
      if (row) row.enabled = enabled
      message.success(enabled ? '已启用' : '已停用')
    } catch (e) {
      message.error('切换启用状态失败')
      console.error(e)
      await refreshCatalog()
    }
  }

  function openToolSchema(tool: ToolCatalogEntry) {
    schemaViewTool.value = tool
    showToolSchemaModal.value = true
  }

  function openToolEdit(tool: ToolCatalogEntry) {
    editingTool.value = tool
    toolConfigModel.value = {
      description: tool.description ?? '',
      timelineSummaryTemplate: tool.timelineSummaryTemplate ?? '',
      timelineSummaryExtract: tool.timelineSummaryExtract ?? '',
    }
    showToolEditModal.value = true
  }

  async function handleSaveToolConfig() {
    if (!editingTool.value) return
    try {
      await toolConfigFormRef.value?.validate()
    } catch {
      return
    }
    saving.value = true
    try {
      const description = toolConfigModel.value.description.trim()
      const template = toolConfigModel.value.timelineSummaryTemplate.trim()
      const extract = toolConfigModel.value.timelineSummaryExtract.trim()
      await patchTool(editingTool.value.id, {
        description,
        timelineSummaryTemplate: template,
        timelineSummaryExtract: extract,
      })
      message.success('工具配置已更新')
      showToolEditModal.value = false
      await refreshCatalog()
    } catch (e) {
      message.error('保存工具配置失败')
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  const toolColumns = computed(() => createToolCatalogColumns({
    enabledMap,
    onToggleTool: handleToggleTool,
    onToggleConfirmation: handleToggleConfirmation,
    onOpenSchema: openToolSchema,
    onOpenEdit: openToolEdit,
  }))

  watch(activeTab, (tab) => {
    routeState.syncQuery({ tab })
    void refreshCurrentTab()
  })

  watch(
    () => route.query.tab,
    () => {
      const tab = routeState.readTab()
      if (activeTab.value !== tab) activeTab.value = tab
    },
  )

  watch(selectedSdkId, (id) => {
    routeState.syncQuery({ sdk: id })
  })

  watch(selectedMcpId, (id) => {
    routeState.syncQuery({ mcp: id })
    mcpActions.resetMcpPanelOnSelect()
  })

  onMounted(() => {
    void refreshCurrentTab()
  })

  return reactive({
    activeTab,
    loading,
    saving,
    syncing,
    probing,
    importing,
    sdkApps,
    mcpServers,
    catalog,
    enabledMap,
    selectedSdkId,
    selectedMcpId,
    showToolEditModal,
    showToolSchemaModal,
    schemaViewTool,
    bindToolConfigFormRef,
    editingTool,
    toolConfigFormRef,
    toolConfigModel,
    toolConfigRules,
    selectedSdk,
    selectedMcp,
    sdkTools,
    mcpTools,
    enabledToolCount,
    toolColumns,
    statusTagType,
    mcpStatusDotClass,
    mcpStatusTitle,
    mcpAvailableToolCount,
    mcpTotalToolCount,
    refreshCurrentTab,
    handleSyncSdk,
    handleSaveToolConfig,
    filterCatalogBySource,
    ...mcpActions,
  })
}

type UnwrapPageMember<T> =
  T extends Ref<infer V> ? V :
  T extends ComputedRef<infer V> ? V :
  T extends (...args: infer A) => infer R ? (...args: A) => R :
  T

type ToolsPageComposable = ReturnType<typeof useToolsPage>

export type ToolsPageApi = {
  [K in keyof ToolsPageComposable]: UnwrapPageMember<ToolsPageComposable[K]>
}
