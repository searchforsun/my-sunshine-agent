import { computed, h, ref, type ComputedRef, type Ref } from 'vue'
import { NIcon, type DropdownOption, type SelectOption } from 'naive-ui'
import {
  CloudDownloadOutline,
  CloudUploadOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import {
  createMcpServer,
  deleteMcpServer,
  exportMcpServers,
  importMcpServers,
  updateMcpServer,
  probeMcpServer,
  type McpServer,
  type McpServerCreateBody,
} from '../api/tools'
import {
  buildMcpServerJson,
  mcpFormDraftToPatch,
  mcpJsonDraftToPatch,
} from '../utils/tools/mcpServerDetail'

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

export interface UseMcpServerActionsDeps {
  message: {
    success: (content: string) => void
    error: (content: string) => void
  }
  mcpServers: Ref<McpServer[]>
  selectedMcpId: Ref<string | null>
  selectedMcp: ComputedRef<McpServer | null>
  saving: Ref<boolean>
  probing: Ref<boolean>
  importing: Ref<boolean>
  refreshMcp: () => Promise<void>
  refreshCatalog: () => Promise<void>
}

export function useMcpServerActions(deps: UseMcpServerActionsDeps) {
  const {
    message,
    mcpServers,
    selectedMcpId,
    selectedMcp,
    saving,
    probing,
    importing,
    refreshMcp,
    refreshCatalog,
  } = deps

  const showMcpCreateModal = ref(false)
  const showMcpDeleteConfirm = ref(false)
  const mcpImportInputRef = ref<HTMLInputElement | null>(null)
  const mcpCreateMode = ref<'form' | 'json'>('form')
  const mcpPanelTab = ref<'config' | 'tools'>('config')
  const mcpDetailMode = ref<'form' | 'json'>('form')
  const mcpDetailEditing = ref(false)

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
  const mcpDetailDraft = ref({
    displayName: '',
    transport: 'stdio',
    command: 'npx',
    argsJson: '[]',
    endpoint: '',
    envJson: '{}',
  })
  const mcpDetailJsonDraft = ref('')

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

  function bindMcpImportInputRef(el: unknown) {
    mcpImportInputRef.value = el instanceof HTMLInputElement ? el : null
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

  function startMcpDetailEdit() {
    syncMcpDetailDraft()
    mcpDetailEditing.value = true
  }

  function cancelMcpDetailEdit() {
    mcpDetailEditing.value = false
    syncMcpDetailDraft()
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
      const body: McpServerCreateBody = {
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

  async function handleCreateMcp() {
    if (mcpCreateMode.value === 'json') {
      await handleCreateMcpJson()
    } else {
      await handleCreateMcpForm()
    }
  }

  async function handleSaveMcpDetail() {
    if (!selectedMcpId.value) return
    saving.value = true
    try {
      const patch = mcpDetailMode.value === 'json'
        ? mcpJsonDraftToPatch(selectedMcpId.value, mcpDetailJsonDraft.value)
        : mcpFormDraftToPatch(mcpDetailDraft.value)
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

  function handleMcpMoreSelect(key: string | number) {
    if (key === 'delete') showMcpDeleteConfirm.value = true
    else if (key === 'import') mcpImportInputRef.value?.click()
    else if (key === 'export') void handleExportMcp()
  }

  function resetMcpPanelOnSelect() {
    mcpDetailEditing.value = false
    mcpPanelTab.value = 'config'
    mcpDetailMode.value = 'form'
    syncMcpDetailDraft()
  }

  return {
    showMcpCreateModal,
    showMcpDeleteConfirm,
    mcpImportInputRef,
    bindMcpImportInputRef,
    mcpCreateMode,
    mcpPanelTab,
    mcpDetailMode,
    mcpDetailEditing,
    mcpCreateDraft,
    mcpJsonDraft,
    mcpDetailDraft,
    mcpDetailJsonDraft,
    transportOptions,
    commandOptions,
    mcpJsonExample,
    canCreateMcpForm,
    canCreateMcpJson,
    mcpMoreMenuOptions,
    buildMcpServerJson,
    syncMcpDetailDraft,
    startMcpDetailEdit,
    cancelMcpDetailEdit,
    openMcpCreate,
    handleCreateMcp,
    handleSaveMcpDetail,
    handleToggleMcpServer,
    handleDeleteMcp,
    handleProbeMcp,
    handleImportMcp,
    handleMcpMoreSelect,
    resetMcpPanelOnSelect,
  }
}
