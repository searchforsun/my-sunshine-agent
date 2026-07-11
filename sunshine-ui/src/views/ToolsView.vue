<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  NButton,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NSwitch,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  NDataTable,
  useMessage,
  type DataTableColumns,
  type DropdownOption,
  type SelectOption,
} from 'naive-ui'
import {
  AddOutline,
  CloudDownloadOutline,
  CloudUploadOutline,
  CreateOutline,
  EllipsisHorizontal,
  RefreshOutline,
  SearchOutline,
  SyncOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import ToolsetTabPanel from '../components/tools/ToolsetTabPanel.vue'
import {
  createMcpServer,
  deleteMcpServer,
  exportMcpServers,
  filterMcpTools,
  filterSdkTools,
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
import { formatSkillVersionTime } from '../utils/formatSkillVersionTime'
import { useToolsRouteState, type ToolsTab } from '../composables/useToolsRouteState'

type TabKey = ToolsTab

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
const mcpCreateMode = ref<'form' | 'json'>('form')
const editingTool = ref<ToolCatalogEntry | null>(null)
const editDescription = ref('')

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
  return filterSdkTools(catalog.value, selectedSdkId.value)
})

const mcpTools = computed(() => {
  if (!selectedMcpId.value) return []
  return filterMcpTools(catalog.value, selectedMcpId.value)
})

const enabledToolCount = computed(() =>
  catalog.value.filter(t => enabledMap.value.get(t.id) === true).length,
)

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

const canCreateMcpForm = computed(() =>
  mcpCreateDraft.value.id.trim().length > 0
  && (
    (mcpCreateDraft.value.transport === 'stdio' && mcpCreateDraft.value.command.trim().length > 0)
    || (mcpCreateDraft.value.transport === 'sse' && mcpCreateDraft.value.endpoint.trim().length > 0)
  ),
)

const canCreateMcpJson = computed(() => mcpJsonDraft.value.trim().length > 0)

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
  return filterMcpTools(catalog.value, serverId)
    .filter(tool => enabledMap.value.get(tool.id) === true)
    .length
}

function mcpTotalToolCount(serverId: string): number {
  return filterMcpTools(catalog.value, serverId).length
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

function handleMcpMoreSelect(key: string | number) {
  if (key === 'delete') showMcpDeleteConfirm.value = true
  else if (key === 'import') mcpImportInputRef.value?.click()
  else if (key === 'export') void handleExportMcp()
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
    width: 140,
    render: (row) => h(NSpace, { size: 4, align: 'center' }, {
      default: () => [hSchemaBtn(row), hEditBtn(row)],
    }),
  },
]

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
    default: () => '描述',
  })
}

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
  editDescription.value = tool.description ?? ''
  showToolEditModal.value = true
}

async function handleSaveDescription() {
  if (!editingTool.value) return
  saving.value = true
  try {
    await patchTool(editingTool.value.id, { description: editDescription.value })
    message.success('描述已更新')
    showToolEditModal.value = false
    await refreshCatalog()
  } catch (e) {
    message.error('保存描述失败')
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
</script>

<template>
  <div class="tools-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>工具管理</h2>
      </div>
      <NSpace :size="8">
        <NButton round type="primary" class="action-btn" :loading="loading" @click="refreshCurrentTab">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <NTabs v-model:value="activeTab" type="line" :animated="false" class="tools-tabs">
      <NTabPane name="sdk" tab="SDK 应用" />
      <NTabPane name="mcp" tab="MCP 服务" />
      <NTabPane name="platform" tab="平台工具" disabled />
      <NTabPane name="toolset" tab="工具集配置" />
    </NTabs>

    <!-- SDK Tab -->
    <div v-if="activeTab === 'sdk'" class="tools-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">应用</span>
          <NTag :bordered="false" size="tiny" round>{{ sdkApps.length }}</NTag>
        </div>
        <NSpin :show="loading" size="small" class="list-spin">
          <div class="list-body">
            <div v-if="sdkApps.length" class="item-list">
              <button
                v-for="app in sdkApps"
                :key="app.id"
                type="button"
                class="item-row"
                :class="{ active: app.id === selectedSdkId }"
                @click="selectedSdkId = app.id"
              >
                <div class="item-row-head">
                  <span class="item-name">{{ app.displayName || app.id }}</span>
                  <NTag :type="statusTagType(app.status)" size="tiny" round :bordered="false">
                    {{ app.status }}
                  </NTag>
                </div>
                <span class="item-id">{{ app.id }}</span>
              </button>
            </div>
            <div v-else-if="!loading" class="empty-wrap">
              <NEmpty size="small" description="暂无 SDK 应用" />
            </div>
          </div>
        </NSpin>
      </aside>

      <main v-if="selectedSdk" class="detail-panel">
        <div class="detail-toolbar">
          <div class="detail-toolbar-text">
            <h3 class="detail-heading">{{ selectedSdk.displayName || selectedSdk.id }}</h3>
            <span class="detail-id">{{ selectedSdk.nacosService }} · schema v{{ selectedSdk.schemaVersion }}</span>
          </div>
          <NButton
            size="small"
            round
            type="primary"
            class="action-btn"
            :loading="syncing"
            @click="handleSyncSdk"
          >
            <template #icon><NIcon :component="SyncOutline" /></template>
            同步 Catalog
          </NButton>
        </div>
        <div class="detail-scroll">
          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">工具列表</h4>
              <NTag :bordered="false" size="tiny" round>{{ sdkTools.length }}</NTag>
            </header>
            <NDataTable
              v-if="sdkTools.length"
              :columns="toolColumns"
              :data="sdkTools"
              :bordered="false"
              size="small"
              class="tools-table"
            />
            <NEmpty v-else size="small" description="暂无工具，请先同步" />
          </section>
        </div>
      </main>
      <main v-else class="detail-panel detail-empty">
        <NEmpty description="选择左侧 SDK 应用" />
      </main>
    </div>

    <!-- MCP Tab -->
    <div v-else-if="activeTab === 'mcp'" class="tools-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">服务</span>
          <NTag :bordered="false" size="tiny" round>{{ mcpServers.length }}</NTag>
          <NButton
            size="tiny"
            quaternary
            class="panel-create-btn"
            @click="openMcpCreate"
          >
            <template #icon><NIcon :component="AddOutline" :size="14" /></template>
            新建 MCP
          </NButton>
        </div>
        <NSpin :show="loading" size="small" class="list-spin">
          <div class="list-body">
            <div v-if="mcpServers.length" class="item-list">
              <div
                v-for="server in mcpServers"
                :key="server.id"
                class="item-row-wrap"
              >
                <button
                  type="button"
                  class="item-row"
                  :class="{ active: server.id === selectedMcpId }"
                  @click="selectedMcpId = server.id"
                >
                  <div class="item-row-head">
                    <span class="item-name">{{ server.displayName || server.id }}</span>
                    <NSwitch
                      size="small"
                      :value="server.enabled"
                      @click.stop
                      @update:value="(v: boolean) => handleToggleMcpServer(server, v)"
                    />
                  </div>
                  <div class="item-row-foot">
                    <span class="item-meta">
                      <span
                        class="pulse-dot"
                        :class="mcpStatusDotClass(server)"
                        :title="mcpStatusTitle(server)"
                        aria-hidden="true"
                      />
                      <span class="item-id">{{ server.transport }} · {{ server.id }}</span>
                    </span>
                    <span class="item-tool-count">
                      {{ mcpAvailableToolCount(server.id) }}/{{ mcpTotalToolCount(server.id) }} 可用
                    </span>
                  </div>
                </button>
              </div>
            </div>
            <div v-else-if="!loading" class="empty-wrap">
              <NEmpty size="small" description="暂无 MCP 服务" />
            </div>
          </div>
        </NSpin>
      </aside>

      <main v-if="selectedMcp" class="detail-panel">
        <div class="detail-toolbar">
          <div class="detail-toolbar-text">
            <h3 class="detail-heading">{{ selectedMcp.displayName || selectedMcp.id }}</h3>
            <span class="detail-id">{{ selectedMcp.transport }} · {{ selectedMcp.id }}</span>
          </div>
          <NSpace :size="8" align="center">
            <NButton
              size="small"
              round
              type="primary"
              class="action-btn"
              :loading="probing"
              @click="handleProbeMcp"
            >
              <template #icon><NIcon :component="SyncOutline" /></template>
              探测
            </NButton>
            <NDropdown
              trigger="click"
              size="small"
              :options="mcpMoreMenuOptions"
              :disabled="probing || importing || saving"
              @select="handleMcpMoreSelect"
            >
              <NButton
                size="small"
                quaternary
                class="more-menu-btn"
                title="更多操作"
                aria-label="更多操作"
                :disabled="probing || importing || saving"
              >
                <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
              </NButton>
            </NDropdown>
          </NSpace>
        </div>
        <div class="detail-scroll">
          <section class="form-section mcp-detail-section">
            <NTabs
              v-model:value="mcpPanelTab"
              type="line"
              :animated="false"
              class="mcp-panel-tabs"
            >
              <NTabPane name="config" tab="连接配置">
                <div class="mcp-config-pane">
                  <header class="mcp-config-toolbar-row">
                    <div class="view-switch">
                      <button
                        type="button"
                        class="view-btn"
                        :class="{ active: mcpDetailMode === 'form' }"
                        :disabled="mcpDetailEditing"
                        @click="mcpDetailMode = 'form'"
                      >
                        表单
                      </button>
                      <button
                        type="button"
                        class="view-btn"
                        :class="{ active: mcpDetailMode === 'json' }"
                        :disabled="mcpDetailEditing"
                        @click="mcpDetailMode = 'json'"
                      >
                        mcp.json
                      </button>
                    </div>
                    <div class="mcp-config-actions">
                      <NButton
                        v-if="!mcpDetailEditing"
                        size="small"
                        round
                        secondary
                        @click="startMcpDetailEdit"
                      >
                        编辑
                      </NButton>
                      <template v-else>
                        <NButton size="small" round secondary :disabled="saving" @click="cancelMcpDetailEdit">
                          取消
                        </NButton>
                        <NButton
                          size="small"
                          round
                          type="primary"
                          class="action-btn"
                          :loading="saving"
                          @click="handleSaveMcpDetail"
                        >
                          保存
                        </NButton>
                      </template>
                    </div>
                  </header>
                  <div v-if="!mcpDetailEditing && mcpDetailMode === 'form'" class="info-grid">
                    <div class="info-item">
                      <span class="info-label">展示名</span>
                      <span class="info-value">{{ selectedMcp.displayName || selectedMcp.id }}</span>
                    </div>
                    <div class="info-item">
                      <span class="info-label">Transport</span>
                      <span class="info-value">{{ selectedMcp.transport }}</span>
                    </div>
                    <template v-if="selectedMcp.transport === 'stdio'">
                      <div class="info-item">
                        <span class="info-label">Command</span>
                        <span class="info-value mono">{{ selectedMcp.command || '—' }}</span>
                      </div>
                      <div class="info-item">
                        <span class="info-label">Args</span>
                        <span class="info-value mono">{{ selectedMcp.argsJson || '[]' }}</span>
                      </div>
                      <div class="info-item">
                        <span class="info-label">Env</span>
                        <span class="info-value mono">{{ selectedMcp.envJson || '{}' }}</span>
                      </div>
                    </template>
                    <div v-else-if="selectedMcp.endpoint" class="info-item">
                      <span class="info-label">Endpoint</span>
                      <span class="info-value mono">{{ selectedMcp.endpoint }}</span>
                    </div>
                  </div>
                  <pre
                    v-else-if="!mcpDetailEditing && mcpDetailMode === 'json'"
                    class="schema-preview mcp-json-preview"
                  >{{ buildMcpServerJson(selectedMcp) }}</pre>
                  <NForm
                    v-else-if="mcpDetailEditing && mcpDetailMode === 'form'"
                    class="modal-form"
                    label-placement="top"
                    :show-feedback="false"
                  >
                    <NFormItem label="展示名">
                      <NInput v-model:value="mcpDetailDraft.displayName" class="sun-field" placeholder="可选" />
                    </NFormItem>
                    <NFormItem label="Transport" required>
                      <NSelect
                        v-model:value="mcpDetailDraft.transport"
                        class="sun-field"
                        :options="transportOptions"
                      />
                    </NFormItem>
                    <NFormItem v-if="mcpDetailDraft.transport === 'stdio'" label="Command" required>
                      <NSelect
                        v-model:value="mcpDetailDraft.command"
                        class="sun-field"
                        filterable
                        tag
                        :options="commandOptions"
                      />
                    </NFormItem>
                    <NFormItem v-if="mcpDetailDraft.transport === 'stdio'" label="Args（JSON 数组）">
                      <NInput
                        v-model:value="mcpDetailDraft.argsJson"
                        class="sun-field"
                        type="textarea"
                        :autosize="{ minRows: 2, maxRows: 6 }"
                      />
                    </NFormItem>
                    <NFormItem v-if="mcpDetailDraft.transport === 'stdio'" label="Env（JSON 对象）">
                      <NInput
                        v-model:value="mcpDetailDraft.envJson"
                        class="sun-field"
                        type="textarea"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                      />
                    </NFormItem>
                    <NFormItem v-else label="Endpoint" required>
                      <NInput v-model:value="mcpDetailDraft.endpoint" class="sun-field" placeholder="http://..." />
                    </NFormItem>
                  </NForm>
                  <NInput
                    v-else
                    v-model:value="mcpDetailJsonDraft"
                    class="sun-field mcp-json-editor"
                    type="textarea"
                    :autosize="{ minRows: 12, maxRows: 24 }"
                    placeholder="mcp.json 内容"
                  />
                  <div v-if="selectedMcp.lastProbeAt || selectedMcp.probeError" class="mcp-probe-meta">
                    <div v-if="selectedMcp.lastProbeAt" class="info-item">
                      <span class="info-label">上次探测</span>
                      <span class="info-value">{{ formatSkillVersionTime(selectedMcp.lastProbeAt) }}</span>
                    </div>
                    <div v-if="selectedMcp.probeError" class="info-item">
                      <span class="info-label">探测错误</span>
                      <span class="info-value error">{{ selectedMcp.probeError }}</span>
                    </div>
                  </div>
                </div>
              </NTabPane>
              <NTabPane name="tools">
                <template #tab>
                  <span class="mcp-tools-tab-label">工具列表</span>
                  <NTag :bordered="false" size="tiny" round>{{ mcpTools.length }}</NTag>
                </template>
                <div class="mcp-tools-pane">
                  <NDataTable
                    v-if="mcpTools.length"
                    :columns="toolColumns"
                    :data="mcpTools"
                    :bordered="false"
                    size="small"
                    class="tools-table"
                  />
                  <NEmpty v-else size="small" description="暂无工具，请先探测" />
                </div>
              </NTabPane>
            </NTabs>
          </section>
        </div>
      </main>
      <main v-else class="detail-panel detail-empty">
        <NEmpty description="选择左侧 MCP 服务，或新建" />
      </main>
    </div>

    <!-- Platform Tab (disabled) -->
    <div v-else-if="activeTab === 'platform'" class="tools-layout">
      <main class="detail-panel detail-empty full-width">
        <NEmpty description="平台内置工具（Phase 2）尚未开放" />
      </main>
    </div>

    <!-- Toolset Tab -->
    <div v-else-if="activeTab === 'toolset'" class="tools-layout toolset-layout">
      <ToolsetTabPanel />
    </div>

    <!-- Edit description modal -->
    <NModal
      v-model:show="showToolEditModal"
      preset="dialog"
      title="编辑工具描述"
      class="sunshine-dialog tool-desc-dialog"
      style="width: 720px; max-width: 94vw;"
    >
      <div class="tool-desc-meta">
        <div class="tool-desc-name">{{ editingTool?.displayName }}</div>
        <div class="tool-desc-id">{{ editingTool?.id }}</div>
      </div>
      <NInput
        v-model:value="editDescription"
        class="tool-desc-input sun-field"
        type="textarea"
        :autosize="{ minRows: 10, maxRows: 20 }"
        placeholder="工具描述（可覆盖 SDK/MCP 默认值）"
      />
      <template #action>
        <NButton @click="showToolEditModal = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="saving" @click="handleSaveDescription">保存</NButton>
      </template>
    </NModal>

    <!-- Tool schema modal -->
    <NModal
      v-model:show="showToolSchemaModal"
      preset="dialog"
      title="Schema 预览"
      class="sunshine-dialog tool-schema-dialog"
      style="width: 800px; max-width: 94vw;"
    >
      <div class="tool-desc-meta">
        <div class="tool-desc-name">{{ schemaViewTool?.displayName }}</div>
        <div class="tool-desc-id">{{ schemaViewTool?.id }}</div>
      </div>
      <pre class="schema-preview tool-schema-preview">{{ JSON.stringify(schemaViewTool?.parameters ?? {}, null, 2) }}</pre>
      <template #action>
        <NButton type="primary" class="action-btn" @click="showToolSchemaModal = false">关闭</NButton>
      </template>
    </NModal>

    <!-- Create MCP modal -->
    <NModal
      v-model:show="showMcpCreateModal"
      preset="dialog"
      title="新建 MCP 服务"
      class="sunshine-dialog mcp-dialog"
      style="width: 640px; max-width: 92vw;"
    >
      <NTabs v-model:value="mcpCreateMode" type="segment" size="small" class="mcp-modal-tabs">
        <NTabPane name="form" tab="表单" />
        <NTabPane name="json" tab="mcp.json" />
      </NTabs>
      <NForm v-if="mcpCreateMode === 'form'" class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem label="服务 ID" required>
          <NInput v-model:value="mcpCreateDraft.id" class="sun-field" placeholder="my-mcp-server" />
        </NFormItem>
        <NFormItem label="展示名">
          <NInput v-model:value="mcpCreateDraft.displayName" class="sun-field" placeholder="可选" />
        </NFormItem>
        <NFormItem label="Transport" required>
          <NSelect
            v-model:value="mcpCreateDraft.transport"
            class="sun-field"
            :options="transportOptions"
          />
        </NFormItem>
        <NFormItem v-if="mcpCreateDraft.transport === 'stdio'" label="Command" required>
          <NSelect
            v-model:value="mcpCreateDraft.command"
            class="sun-field"
            filterable
            tag
            :options="commandOptions"
          />
        </NFormItem>
        <NFormItem v-if="mcpCreateDraft.transport === 'stdio'" label="Args（JSON 数组）">
          <NInput
            v-model:value="mcpCreateDraft.argsJson"
            class="sun-field"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder='["-y", "@modelcontextprotocol/server-filesystem", "/data"]'
          />
        </NFormItem>
        <NFormItem v-if="mcpCreateDraft.transport === 'stdio'" label="Env（JSON 对象，可选）">
          <NInput
            v-model:value="mcpCreateDraft.envJson"
            class="sun-field"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder='{"KEY": "value"}'
          />
        </NFormItem>
        <NFormItem v-else label="Endpoint" required>
          <NInput v-model:value="mcpCreateDraft.endpoint" class="sun-field" placeholder="http://..." />
        </NFormItem>
      </NForm>
      <NForm v-else class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem label="粘贴 Cursor 兼容 mcp.json">
          <NInput
            v-model:value="mcpJsonDraft"
            class="sun-field"
            type="textarea"
            :autosize="{ minRows: 10, maxRows: 18 }"
            placeholder="mcp.json 内容"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showMcpCreateModal = false">取消</NButton>
        <NButton
          type="primary"
          class="action-btn"
          :loading="saving"
          :disabled="mcpCreateMode === 'form' ? !canCreateMcpForm : !canCreateMcpJson"
          @click="handleCreateMcp"
        >
          {{ mcpCreateMode === 'json' ? '导入' : '创建' }}
        </NButton>
      </template>
    </NModal>

    <!-- Delete MCP confirm -->
    <NModal
      v-model:show="showMcpDeleteConfirm"
      preset="dialog"
      title="删除 MCP 服务"
      class="sunshine-dialog"
    >
      <p>确定删除「{{ selectedMcp?.displayName || selectedMcp?.id }}」？关联工具将从 Catalog 移除。</p>
      <template #action>
        <NButton @click="showMcpDeleteConfirm = false">取消</NButton>
        <NButton type="error" :loading="saving" @click="handleDeleteMcp">删除</NButton>
      </template>
    </NModal>
    <input
      ref="mcpImportInputRef"
      type="file"
      accept=".json,application/json"
      class="hidden-file"
      @change="handleImportMcp"
    />
  </div>
</template>

<style scoped>
.tools-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--sun-text);
}

.tools-tabs {
  flex-shrink: 0;
}

.tools-tabs :deep(.n-tabs-nav) {
  padding: 0 2px;
}

.tools-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.toolset-layout {
  grid-template-columns: 1fr;
}

.list-panel,
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.full-width {
  grid-column: 1 / -1;
}

.list-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-create-btn {
  margin-left: auto;
}

.list-spin {
  flex: 1;
  min-height: 0;
}

.list-spin :deep(.n-spin-content) {
  height: 100%;
}

.list-body {
  padding: 12px 14px 14px;
  min-height: 0;
  overflow: auto;
}

.empty-wrap {
  padding: 24px 0;
}

.detail-panel {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.detail-toolbar-text {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.detail-heading {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.form-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.item-row-wrap {
  display: flex;
  flex-direction: column;
}

.more-menu-btn {
  padding: 0 6px;
}

:deep(.more-menu-delete) {
  color: var(--n-color-error);
}

.mcp-modal-tabs {
  margin-bottom: 12px;
}

.mcp-modal-tabs :deep(.n-tabs-pane-wrapper) {
  display: none;
}

.mcp-detail-section {
  padding-top: 8px;
  gap: 0;
}

.mcp-panel-tabs :deep(.n-tabs-nav) {
  padding: 0 4px;
  margin-bottom: 16px;
}

.mcp-config-pane {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mcp-config-toolbar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 4px;
}

.view-switch {
  display: inline-flex;
  flex-shrink: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.view-btn {
  border: none;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: 13px;
  line-height: 1;
  padding: 7px 14px;
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.15s ease;
}

.view-btn + .view-btn {
  border-left: 1px solid var(--sun-border);
}

.view-btn.active {
  color: var(--sun-text);
  font-weight: 600;
}

.view-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.view-btn:not(:disabled):hover {
  color: var(--sun-text);
}

.mcp-tools-tab-label {
  margin-right: 6px;
}

.mcp-tools-pane {
  min-height: 120px;
}

.mcp-config-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

.mcp-json-preview {
  max-height: 360px;
}

.mcp-json-editor :deep(.n-input__textarea-el) {
  font-family: var(--sun-font-mono, monospace);
  font-size: 12px;
  line-height: 1.5;
}

.mcp-probe-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 12px;
  margin-top: 4px;
  border-top: 1px solid var(--sun-border);
}

.modal-form :deep(.n-form-item) {
  margin-bottom: 12px;
}

.modal-form :deep(.n-input) {
  --n-color: var(--sun-black) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-box-shadow-focus: none !important;
}

.modal-form :deep(.n-select) {
  --n-color: var(--sun-black) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-box-shadow-focus: none !important;
}

.item-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.item-row:hover {
  border-color: var(--sun-border-light);
}

.item-row.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.item-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.item-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-row-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.item-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.item-tool-count {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  white-space: nowrap;
}

.tools-table :deep(.n-data-table) {
  --n-th-color: var(--sun-black);
  --n-td-color: var(--sun-black);
  --n-border-color: var(--sun-border);
}

.schema-preview.tool-schema-preview {
  max-height: min(560px, 62vh);
}

:global(.sunshine-dialog.tool-schema-dialog.n-dialog) {
  max-width: 800px;
  width: min(800px, 94vw);
}

:global(.sunshine-dialog.tool-schema-dialog .n-dialog__content) {
  white-space: normal;
}

.schema-preview {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  font-size: 12px;
  font-family: var(--sun-font-mono, monospace);
  overflow: auto;
  max-height: 240px;
}

.tool-desc-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.tool-desc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.tool-desc-id {
  font-size: 12px;
  font-family: var(--sun-font-mono, monospace);
  color: var(--sun-text-muted);
  word-break: break-all;
}

.tool-desc-input {
  width: 100%;
  --n-border-radius: var(--radius-md) !important;
}

.tool-desc-input :deep(.n-input__textarea-el) {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.6;
  padding: 12px 14px;
  min-height: 220px;
}

:global(.sunshine-dialog.tool-desc-dialog.n-dialog) {
  max-width: 720px;
  width: min(720px, 94vw);
}

:global(.sunshine-dialog.tool-desc-dialog .n-dialog__content) {
  white-space: normal;
}

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info-label {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.info-value {
  font-size: 14px;
  color: var(--sun-text);
}

.info-value.mono {
  font-family: var(--sun-font-mono, monospace);
  word-break: break-all;
}

.info-value.error {
  color: var(--n-color-error, #e88080);
}

.hidden-file {
  display: none;
}

.toolset-toolbar {
  gap: 12px;
}

.toolset-search {
  flex: 1;
  min-width: 180px;
  max-width: 360px;
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
  --n-border-radius: var(--radius-md) !important;
}

.toolset-toolbar-actions {
  flex-shrink: 0;
  margin-left: auto;
}

.toolset-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.toolset-spin :deep(.n-spin-container) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.toolset-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.toolset-inherit-hint {
  margin: 0 0 12px;
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md, 10px);
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.5;
}

.toolset-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.tool-pool-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-pool-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.tool-pool-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tool-pool-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.plan-policy-panel {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 4px;
}

.plan-policy-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.plan-policy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.plan-policy-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.plan-policy-label {
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.plan-policy-control {
  width: 100%;
}

.plan-policy-panel :deep(.n-input),
.plan-policy-panel :deep(.n-input-number),
.plan-policy-panel :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-arrow-color: var(--sun-text-secondary) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-active: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
  --n-box-shadow-hover: none !important;
  --n-box-shadow-active: none !important;
  --n-border-radius: var(--radius-md) !important;
}

.tool-pool-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.tool-pool-id {
  font-size: 12px;
  font-family: var(--sun-font-mono, monospace);
  color: var(--sun-text-muted);
  word-break: break-all;
}

.tool-pool-desc {
  font-size: 13px;
  line-height: 1.5;
  color: var(--sun-text-secondary);
  margin-top: 2px;
}

.tool-pool-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.tool-pool-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-pool-toggle-label {
  font-size: 12px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.toolset-subtabs {
  padding: 0 16px;
  border-bottom: 1px solid var(--sun-border);
}

.toolset-subtabs :deep(.n-tabs-nav) {
  background: transparent;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-border: none !important;
}

@media (max-width: 960px) {
  .tools-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
}
</style>
