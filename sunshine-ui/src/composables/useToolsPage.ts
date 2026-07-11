import { computed, h, onMounted, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  NButton,
  NIcon,
  NSpace,
  NSwitch,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns,
  type DropdownOption,
  type FormInst,
  type FormRules,
  type SelectOption,
} from 'naive-ui'
import {
  CloudDownloadOutline,
  CloudUploadOutline,
  CreateOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import {
  createMcpServer,
  deleteMcpServer,
  exportMcpServers,
  filterCatalogBySource,
  importMcpServers,
  listMcpServers,
  listSdkApplications,
  listToolCatalog,
  buildToolEnabledMap,
  patchTool,
  probeMcpServer,
  syncSdkApplication,
  updateMcpServer,
  type McpServer,
  type McpServerPatchBody,
  type SdkApplication,
  type ToolCatalogEntry,
} from '../api/tools'
import type { TenantId } from '../api/tenants'
import { useToolsRouteState, type ToolsTab } from './useToolsRouteState'
import {
  formatTimelineExtractHint,
  formatTimelineTemplateLabel,
} from '../utils/toolTimelineDisplay'

export const TOOLS_PAGE_KEY = Symbol('toolsPage')

type TabKey = ToolsTab

const transportOptions: SelectOption[] = [
  { label: 'stdio（本地进程）', value: 'stdio' },
  { label: 'sse（HTTP 远程）', value: 'sse' },
]

const commandOptions: SelectOption[] = [
  { label: 'npx', value: 'npx' },
  { label: 'node', value: 'node' },
  { label: 'python', value: 'python' },
  { label: 'uvx', value: 'uvx' },
]

const mcpJsonExample = `{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
    }
  }
}`

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

  const showMcpCreateModal = ref(false)
  const showMcpDeleteConfirm = ref(false)
  const showToolEditModal = ref(false)
  const showToolSchemaModal = ref(false)
  const schemaViewTool = ref<ToolCatalogEntry | null>(null)
  const mcpImportInputRef = ref<HTMLInputElement | null>(null)

  function bindMcpImportInputRef(el: unknown) {
    mcpImportInputRef.value = el instanceof HTMLInputElement ? el : null
  }

  function bindToolConfigFormRef(el: unknown) {
    toolConfigFormRef.value = el as FormInst | null
  }
  const mcpCreateMode = ref<'form' | 'json'>('form')
  const editingTool = ref<ToolCatalogEntry | null>(null)
  const toolConfigFormRef = ref<FormInst | null>(null)
  const toolConfigModel = ref({
    description: '',
    timelineSummaryTemplate: '',
    timelineSummaryExtract: '',
  })

  const mcpCreateDraft = ref({
    id: '',
    displayName: '',
    transport: 'stdio',
    command: 'npx',
    argsJson: '[]',
    endpoint: '',
    envJson: '{}',
  })

  const mcpJsonDraft = ref('')

  const mcpPanelTab = ref<'config' | 'tools'>('config')
  const mcpDetailMode = ref<'form' | 'json'>('form')
  const mcpDetailEditing = ref(false)
  const mcpDetailDraft = ref({
    displayName: '',
    transport: 'stdio',
    command: 'npx',
    argsJson: '[]',
    endpoint: '',
    envJson: '{}',
  })
  const mcpDetailJsonDraft = ref('')

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

  const canCreateMcpForm = computed(() =>
    mcpCreateDraft.value.id.trim().length > 0
    && (
      (mcpCreateDraft.value.transport === 'stdio' && mcpCreateDraft.value.command.trim().length > 0)
      || (mcpCreateDraft.value.transport === 'sse' && mcpCreateDraft.value.endpoint.trim().length > 0)
    ),
  )

  const canCreateMcpJson = computed(() => mcpJsonDraft.value.trim().length > 0)

  const mcpMoreMenuOptions: DropdownOption[] = [
    {
      label: '导入',
      key: 'import',
      icon: () => h(NIcon, { component: CloudUploadOutline, size: 14 }),
    },
    {
      label: '导出',
      key: 'export',
      icon: () => h(NIcon, { component: CloudDownloadOutline, size: 14 }),
    },
    { type: 'divider', key: 'divider-mcp-delete' },
    {
      label: () => h('span', { class: 'more-menu-delete' }, '删除'),
      key: 'delete',
      icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
    },
  ]

  function sideEffectLabel(sideEffect: string): string {
    return sideEffect === 'write' ? '写' : '读'
  }

  function sideEffectTagType(sideEffect: string): 'warning' | 'default' {
    return sideEffect === 'write' ? 'warning' : 'default'
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

  function buildMcpServerJson(server: McpServer): string {
    const config: Record<string, unknown> = {}
    if (server.transport === 'sse') {
      config.url = server.endpoint ?? ''
    } else {
      config.command = server.command ?? ''
      try {
        config.args = JSON.parse(server.argsJson || '[]')
      } catch {
        config.args = []
      }
      try {
        const env = JSON.parse(server.envJson || '{}') as Record<string, string>
        config.env = env
      } catch {
        config.env = {}
      }
    }
    return JSON.stringify({ mcpServers: { [server.id]: config } }, null, 2)
  }

  function syncMcpDetailDraft() {
    const server = selectedMcp.value
    if (!server) return
    mcpDetailDraft.value = {
      displayName: server.displayName ?? '',
      transport: server.transport,
      command: server.command ?? 'npx',
      argsJson: server.argsJson ?? '[]',
      endpoint: server.endpoint ?? '',
      envJson: server.envJson ?? '{}',
    }
    mcpDetailJsonDraft.value = buildMcpServerJson(server)
  }

  function mcpFormDraftToPatch(): McpServerPatchBody {
    const draft = mcpDetailDraft.value
    return {
      displayName: draft.displayName.trim() || undefined,
      transport: draft.transport,
      command: draft.transport === 'stdio' ? draft.command.trim() : '',
      argsJson: draft.transport === 'stdio' ? draft.argsJson.trim() || '[]' : '[]',
      endpoint: draft.transport === 'sse' ? draft.endpoint.trim() : '',
      envJson: draft.envJson.trim() || '{}',
    }
  }

  function mcpJsonDraftToPatch(serverId: string): McpServerPatchBody {
    let root: { mcpServers?: Record<string, Record<string, unknown>> }
    try {
      root = JSON.parse(mcpDetailJsonDraft.value.trim()) as typeof root
    } catch {
      throw new Error('JSON 格式无效')
    }
    const config = root.mcpServers?.[serverId]
    if (!config) throw new Error(`mcpServers 中缺少 "${serverId}"`)
    const url = config.url
    if (url != null && String(url).trim()) {
      return {
        transport: 'sse',
        endpoint: String(url).trim(),
        command: '',
        argsJson: '[]',
        envJson: '{}',
      }
    }
    return {
      transport: 'stdio',
      command: config.command == null ? '' : String(config.command),
      argsJson: JSON.stringify(config.args ?? []),
      envJson: JSON.stringify(config.env ?? {}),
      endpoint: '',
    }
  }

  function startMcpDetailEdit() {
    syncMcpDetailDraft()
    mcpDetailEditing.value = true
  }

  function cancelMcpDetailEdit() {
    mcpDetailEditing.value = false
    syncMcpDetailDraft()
  }

  async function handleSaveMcpDetail() {
    if (!selectedMcpId.value) return
    saving.value = true
    try {
      const patch = mcpDetailMode.value === 'json'
        ? mcpJsonDraftToPatch(selectedMcpId.value)
        : mcpFormDraftToPatch()
      await updateMcpServer(selectedMcpId.value, patch)
      message.success('MCP 配置已保存')
      mcpDetailEditing.value = false
      await refreshMcp()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存 MCP 配置失败')
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  function handleMcpMoreSelect(key: string | number) {
    if (key === 'delete') showMcpDeleteConfirm.value = true
    else if (key === 'import') mcpImportInputRef.value?.click()
    else if (key === 'export') void handleExportMcp()
  }

  function renderTimelineTemplate(row: ToolCatalogEntry) {
    const label = formatTimelineTemplateLabel(row.timelineSummaryTemplate)
    const extract = formatTimelineExtractHint(row.timelineSummaryExtract)
    return h('div', { class: 'tool-timeline-cell' }, [
      extract
        ? h(NTooltip, { trigger: 'hover', placement: 'top-start' }, {
            trigger: () => h('span', { class: 'tool-timeline-template' }, label),
            default: () => h('pre', { class: 'tool-timeline-extract-tip' }, extract),
          })
        : h('span', { class: 'tool-timeline-template' }, label),
    ])
  }

  function hSwitch(row: ToolCatalogEntry) {
    return h(NSwitch, {
      size: 'small',
      value: enabledMap.value.get(row.id) ?? false,
      disabled: row.idValid === false,
      onUpdateValue: (v: boolean) => handleToggleTool(row, v),
    })
  }

  function hSchemaBtn(row: ToolCatalogEntry) {
    return h(NButton, {
      size: 'tiny',
      quaternary: true,
      onClick: () => openToolSchema(row),
    }, {
      default: () => 'Schema',
    })
  }

  function hEditBtn(row: ToolCatalogEntry) {
    return h(NButton, {
      size: 'tiny',
      quaternary: true,
      onClick: () => openToolEdit(row),
    }, {
      icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
      default: () => '配置',
    })
  }

  const toolColumns: DataTableColumns<ToolCatalogEntry> = [
    { title: '工具 ID', key: 'id', ellipsis: { tooltip: true } },
    { title: '展示名', key: 'displayName', ellipsis: { tooltip: true } },
    {
      title: '读写',
      key: 'sideEffect',
      width: 64,
      render: (row) => h(NTag, {
        size: 'small',
        bordered: false,
        type: sideEffectTagType(row.sideEffect),
      }, { default: () => sideEffectLabel(row.sideEffect) }),
    },
    {
      title: '时间线摘要',
      key: 'timelineSummaryTemplate',
      minWidth: 220,
      render: (row) => renderTimelineTemplate(row),
    },
    {
      title: '人工确认',
      key: 'requireConfirmation',
      width: 88,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.requireConfirmation,
        disabled: row.idValid === false,
        onUpdateValue: (v: boolean) => handleToggleConfirmation(row, v),
      }),
    },
    {
      title: '状态',
      key: 'idValid',
      width: 88,
      render: (row) => row.idValid === false
        ? h(NTag, { type: 'error', size: 'small', bordered: false }, { default: () => '非法 ID' })
        : null,
    },
    {
      title: '启用',
      key: 'enabled',
      width: 72,
      render: (row) => hSwitch(row),
    },
    {
      title: '操作',
      key: 'actions',
      width: 148,
      render: (row) => h(NSpace, { size: 4, align: 'center' }, {
        default: () => [hSchemaBtn(row), hEditBtn(row)],
      }),
    },
  ]

  async function refreshCatalog(tenantId: TenantId = 'default') {
    const all = await listToolCatalog(tenantId, false)
    catalog.value = all
    enabledMap.value = buildToolEnabledMap(all)
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
      if (!mcpDetailEditing.value) {
        syncMcpDetailDraft()
      }
    } catch (e) {
      message.error('加载 MCP 服务失败')
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

  function openMcpCreate() {
    mcpCreateMode.value = 'form'
    mcpCreateDraft.value = {
      id: '',
      displayName: '',
      transport: 'stdio',
      command: 'npx',
      argsJson: '[]',
      endpoint: '',
      envJson: '{}',
    }
    mcpJsonDraft.value = mcpJsonExample
    showMcpCreateModal.value = true
  }

  async function handleCreateMcpForm() {
    if (!canCreateMcpForm.value) return
    saving.value = true
    try {
      const body = {
        id: mcpCreateDraft.value.id.trim(),
        displayName: mcpCreateDraft.value.displayName.trim() || undefined,
        transport: mcpCreateDraft.value.transport,
        command: mcpCreateDraft.value.transport === 'stdio'
          ? mcpCreateDraft.value.command.trim()
          : undefined,
        argsJson: mcpCreateDraft.value.transport === 'stdio'
          ? mcpCreateDraft.value.argsJson.trim() || '[]'
          : undefined,
        endpoint: mcpCreateDraft.value.transport === 'sse'
          ? mcpCreateDraft.value.endpoint.trim()
          : undefined,
        envJson: mcpCreateDraft.value.envJson.trim() || '{}',
        enabled: false,
      }
      const created = await createMcpServer(body)
      message.success('MCP 服务已创建')
      showMcpCreateModal.value = false
      await refreshMcp()
      selectedMcpId.value = created.id
    } catch (e) {
      message.error('创建 MCP 服务失败')
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function handleCreateMcpJson() {
    if (!canCreateMcpJson.value) return
    saving.value = true
    try {
      const imported = await importMcpServers(mcpJsonDraft.value.trim())
      message.success(`已导入 ${imported.length} 个 MCP 服务`)
      showMcpCreateModal.value = false
      await refreshMcp()
      if (imported.length > 0) {
        selectedMcpId.value = imported[0].id
      }
    } catch (e) {
      message.error('mcp.json 格式无效或导入失败')
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function handleToggleMcpServer(server: McpServer, enabled: boolean) {
    try {
      await updateMcpServer(server.id, { enabled })
      server.enabled = enabled
      message.success(enabled ? 'MCP 服务已启用' : 'MCP 服务已停用')
      await refreshCatalog()
    } catch (e) {
      message.error('切换 MCP 服务状态失败')
      console.error(e)
      await refreshMcp()
    }
  }

  async function handleDeleteMcp() {
    if (!selectedMcpId.value) return
    saving.value = true
    try {
      await deleteMcpServer(selectedMcpId.value)
      message.success('MCP 服务已删除')
      showMcpDeleteConfirm.value = false
      selectedMcpId.value = null
      await refreshMcp()
    } catch (e) {
      message.error('删除 MCP 服务失败')
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function handleCreateMcp() {
    if (mcpCreateMode.value === 'json') {
      await handleCreateMcpJson()
    } else {
      await handleCreateMcpForm()
    }
  }

  async function handleProbeMcp() {
    if (!selectedMcpId.value) return
    probing.value = true
    try {
      await probeMcpServer(selectedMcpId.value)
      message.success('探测完成')
      await refreshMcp()
    } catch (e) {
      message.error('探测失败')
      console.error(e)
    } finally {
      probing.value = false
    }
  }

  async function handleImportMcp(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    if (!file) return
    importing.value = true
    try {
      const text = await file.text()
      await importMcpServers(text)
      message.success('导入完成')
      await refreshMcp()
    } catch (e) {
      message.error('导入失败')
      console.error(e)
    } finally {
      importing.value = false
      input.value = ''
    }
  }

  async function handleExportMcp() {
    try {
      const json = await exportMcpServers()
      const blob = new Blob([json], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'mcp.json'
      a.click()
      URL.revokeObjectURL(url)
      message.success('已导出 mcp.json')
    } catch (e) {
      message.error('导出失败')
      console.error(e)
    }
  }

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
    mcpDetailEditing.value = false
    mcpPanelTab.value = 'config'
    mcpDetailMode.value = 'form'
    syncMcpDetailDraft()
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
    showMcpCreateModal,
    showMcpDeleteConfirm,
    showToolEditModal,
    showToolSchemaModal,
    schemaViewTool,
    mcpImportInputRef,
    bindMcpImportInputRef,
    bindToolConfigFormRef,
    mcpCreateMode,
    editingTool,
    toolConfigFormRef,
    toolConfigModel,
    toolConfigRules,
    transportOptions,
    commandOptions,
    mcpJsonExample,
    mcpCreateDraft,
    mcpJsonDraft,
    mcpPanelTab,
    mcpDetailMode,
    mcpDetailEditing,
    mcpDetailDraft,
    mcpDetailJsonDraft,
    selectedSdk,
    selectedMcp,
    sdkTools,
    mcpTools,
    enabledToolCount,
    canCreateMcpForm,
    canCreateMcpJson,
    mcpMoreMenuOptions,
    toolColumns,
    statusTagType,
    mcpStatusDotClass,
    mcpStatusTitle,
    mcpAvailableToolCount,
    mcpTotalToolCount,
    buildMcpServerJson,
    startMcpDetailEdit,
    cancelMcpDetailEdit,
    handleSaveMcpDetail,
    handleMcpMoreSelect,
    refreshCurrentTab,
    handleSyncSdk,
    handleToggleMcpServer,
    openMcpCreate,
    handleCreateMcp,
    handleProbeMcp,
    handleImportMcp,
    handleSaveToolConfig,
    handleDeleteMcp,
    filterCatalogBySource,
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
