<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch } from 'vue'
import type { DropdownOption } from 'naive-ui'
import {
  NButton,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NSwitch,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  useMessage,
} from 'naive-ui'
import {
  AddOutline,
  CloudDownloadOutline,
  CloudOutline,
  CreateOutline,
  EllipsisHorizontal,
  HardwareChipOutline,
  RefreshOutline,
  SearchOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import {
  createAgent,
  deleteAgent,
  fetchAgentCard,
  listAgents,
  setAgentEnabled,
  updateAgent,
  type AgentCardPreFill,
  type AgentEntry,
} from '../api/agents'
import { listSkillCatalogIndex, type SkillCatalogIndexEntry } from '../api/skills'
import { listBizScenes, type BizSceneEntry } from '../api/bizScenes'
import { listToolCatalog, type ToolCatalogEntry } from '../api/tools'
import {
  catalogEnabledModelOptions,
  fetchModelCatalog,
} from '../api/models'
import { listKbs, type KnowledgeBase } from '../api/ragAdmin'
import { useTenantPreference } from '../composables/useTenantPreference'
import { useAgentsRouteState } from '../composables/useAgentsRouteState'

const PLACEHOLDER_PROMPT = '待补充系统提示词'
const AGENT_ID_PATTERN = /^[\w\u4e00-\u9fff-]+$/

const message = useMessage()
const { tenantId } = useTenantPreference()
const { readId, syncId } = useAgentsRouteState()
const loading = ref(false)
const saving = ref(false)
const creating = ref(false)
const deleting = ref(false)
const isEditing = ref(false)
const agents = ref<AgentEntry[]>([])
const skillOptions = ref<SkillCatalogIndexEntry[]>([])
const toolOptions = ref<ToolCatalogEntry[]>([])
const kbOptions = ref<KnowledgeBase[]>([])
const modelSelectOptions = ref<{ label: string; value: string }[]>([])
const selectedId = ref<string | null>(readId())
const activeTab = ref<'internal' | 'external'>('internal')

// ---- 内部智能体 ----
const showInternalCreateModal = ref(false)
const internalCreateDraft = ref({ id: '', displayName: '' })

const editForm = ref({
  displayName: '',
  description: '',
  systemPrompt: '',
  skillIds: [] as string[],
  toolIds: [] as string[],
  kbScope: '' as string,
  dataScope: '',
  permissionsHitl: 'inherit' as string,
  permissionsSandboxWrite: 'inherit' as string,
  modelName: null as string | null,
  maxIters: '',
  maxHandoffs: '',
  kind: 'all' as string,
  bizScene: null as string | null,
})

// ---- 外部智能体 ----
const showExternalCreateModal = ref(false)
const externalCreateDraft = ref({
  id: '',
  displayName: '',
  agentCardUrl: '',
})
const cardPreFill = ref<AgentCardPreFill | null>(null)
const fetchingCard = ref(false)

const externalEdit = ref({
  displayName: '',
  description: '',
  agentCardUrl: '',
  authConfigType: 'bearer' as string,
  authConfigToken: '',
  endpointOverride: '',
})

// ---- 计算属性 ----
const internalAgents = computed(() => agents.value.filter(a => a.source !== 'EXTERNAL'))
const externalAgents = computed(() => agents.value.filter(a => a.source === 'EXTERNAL'))
const filteredAgents = computed(() =>
  activeTab.value === 'internal' ? internalAgents.value : externalAgents.value,
)

const agentSearch = ref('')
const searchedAgents = computed(() => {
  const q = agentSearch.value.trim().toLowerCase()
  const list = filteredAgents.value
  if (!q) return list
  return list.filter(
    e =>
      e.id.toLowerCase().includes(q)
      || (e.displayName ?? '').toLowerCase().includes(q)
      || (e.description ?? '').toLowerCase().includes(q),
  )
})

const selectedAgent = computed(() =>
  agents.value.find(e => e.id === selectedId.value) ?? null,
)

const isExternalSelected = computed(() => selectedAgent.value?.source === 'EXTERNAL')

const skillSelectOptions = computed(() =>
  skillOptions.value.map(s => ({ label: `${s.displayName} (${s.id})`, value: s.id })),
)

const toolSelectOptions = computed(() =>
  toolOptions.value
    .filter(t => t.enabled)
    .map(t => ({ label: `${t.displayName || t.id} (${t.id})`, value: t.id })),
)

const kbSelectOptions = computed(() =>
  kbOptions.value.map(k => ({ label: `${k.displayName} (${k.kbId})`, value: k.kbId })),
)

const enabledToolIds = computed(() =>
  toolOptions.value.filter(t => t.enabled).map(t => t.id),
)

// ---- 工具函数 ----
function parseAgentToolIds(toolsJson: string | undefined | null): string[] {
  if (!toolsJson?.trim()) return []
  try {
    const parsed = JSON.parse(toolsJson) as unknown
    if (!Array.isArray(parsed)) return []
    const ids = parsed.map(x => String(x).trim()).filter(Boolean)
    if (ids.length === 1 && ids[0] === '*') return [...enabledToolIds.value]
    return ids.filter(id => id !== '*')
  } catch {
    return []
  }
}

function parsePermissionsString(json: string | undefined | null, key: string, def: string): string {
  if (!json?.trim()) return def
  try {
    const obj = JSON.parse(json)
    return obj[key] ?? def
  } catch {
    return def
  }
}

function makePermissionsJson(source: Record<string, string>): string {
  const obj: Record<string, string> = {}
  if (source.permissionsHitl !== 'inherit') obj.hitl = source.permissionsHitl
  if (source.permissionsSandboxWrite !== 'inherit') obj.sandboxWriteMode = source.permissionsSandboxWrite
  if (Object.keys(obj).length === 0) return ''
  return JSON.stringify(obj)
}

function parseAuthConfig(agent: AgentEntry): { type: string; token: string } {
  if (!agent.authConfigJson?.trim()) return { type: 'bearer', token: '' }
  try {
    const obj = JSON.parse(agent.authConfigJson)
    return {
      type: obj.type ?? 'bearer',
      token: obj.token ?? obj.key ?? '',
    }
  } catch {
    return { type: 'bearer', token: '' }
  }
}

function makeAuthConfigJson(type: string, token: string): string {
  if (!type || !token.trim()) return ''
  if (type === 'api-key') return JSON.stringify({ type: 'api-key', key: token.trim() })
  return JSON.stringify({ type: 'bearer', token: token.trim() })
}

/** modelConfigJson 读 model（SSOT 字段名 = model） */
function parseModelConfigModel(json: string | undefined | null): string | null {
  if (!json?.trim()) return null
  try {
    const obj = JSON.parse(json) as Record<string, unknown>
    const v = obj.model
    return typeof v === 'string' && v.trim() ? v.trim() : null
  } catch {
    return null
  }
}

function buildModelConfigJson(model: string | null): string {
  if (!model?.trim()) return '{}'
  return JSON.stringify({ model: model.trim() })
}

// ---- 内部创建 ----
const internalIdTrimmed = computed(() => internalCreateDraft.value.id.trim())
const internalNameTrimmed = computed(() => internalCreateDraft.value.displayName.trim())
const internalIdDuplicate = computed(() =>
  internalIdTrimmed.value.length > 0 && agents.value.some(e => e.id === internalIdTrimmed.value),
)
const internalIdInvalid = computed(() =>
  internalIdTrimmed.value.length > 0 && !AGENT_ID_PATTERN.test(internalIdTrimmed.value),
)
const canConfirmInternalCreate = computed(() =>
  internalIdTrimmed.value.length > 0
  && internalNameTrimmed.value.length > 0
  && !internalIdDuplicate.value
  && !internalIdInvalid.value,
)

// ---- 外部创建 ----
const externalIdTrimmed = computed(() => externalCreateDraft.value.id.trim())
const externalNameTrimmed = computed(() => externalCreateDraft.value.displayName.trim())
const externalUrlTrimmed = computed(() => externalCreateDraft.value.agentCardUrl.trim())
const externalIdDuplicate = computed(() =>
  externalIdTrimmed.value.length > 0 && agents.value.some(e => e.id === externalIdTrimmed.value),
)
const externalIdInvalid = computed(() =>
  externalIdTrimmed.value.length > 0 && !AGENT_ID_PATTERN.test(externalIdTrimmed.value),
)
const canConfirmExternalCreate = computed(() =>
  externalIdTrimmed.value.length > 0
  && externalNameTrimmed.value.length > 0
  && externalUrlTrimmed.value.length > 0
  && !externalIdDuplicate.value
  && !externalIdInvalid.value,
)

// ---- 表单完整性 ----
const editFormComplete = computed(() => {
  if (isExternalSelected.value) {
    return !!externalEdit.value.displayName.trim()
  }
  return !!editForm.value.displayName.trim()
    && !!editForm.value.systemPrompt.trim()
    && editForm.value.systemPrompt.trim() !== PLACEHOLDER_PROMPT
})

// ---- Dirty ----
const isFormDirty = computed(() => {
  const agent = selectedAgent.value
  if (!agent) return false
  if (agent.source === 'EXTERNAL') {
    const auth = parseAuthConfig(agent)
    return externalEdit.value.displayName !== agent.displayName
      || externalEdit.value.description !== (agent.description ?? '')
      || externalEdit.value.agentCardUrl !== (agent.agentCardUrl ?? '')
      || externalEdit.value.authConfigType !== auth.type
      || externalEdit.value.authConfigToken !== auth.token
      || externalEdit.value.endpointOverride !== (agent.endpointOverride ?? '')
  }
  return editForm.value.displayName !== agent.displayName
    || editForm.value.description !== (agent.description ?? '')
    || editForm.value.systemPrompt !== agent.systemPrompt
    || JSON.stringify([...editForm.value.skillIds].sort())
      !== JSON.stringify([...(agent.skillIds ?? [])].sort())
    || JSON.stringify([...editForm.value.toolIds].sort())
      !== JSON.stringify([...parseAgentToolIds(agent.toolsJson)].sort())
    || editForm.value.kbScope !== ((agent.kbScope ?? []).length > 0 ? agent.kbScope![0] : '')
    || editForm.value.dataScope !== (agent.dataScopeJson ?? '')
    || editForm.value.permissionsHitl !== parsePermissionsString(agent.permissionsJson, 'hitl', 'inherit')
    || editForm.value.permissionsSandboxWrite !== parsePermissionsString(agent.permissionsJson, 'sandboxWriteMode', 'inherit')
    || editForm.value.modelName !== parseModelConfigModel(agent.modelConfigJson)
    || editForm.value.maxIters !== String(agent.maxIters ?? 0)
    || editForm.value.maxHandoffs !== String(agent.maxHandoffs ?? 0)
    || editForm.value.kind !== (agent.kind ?? 'all')
    || editForm.value.bizScene !== (agent.bizScene ?? null)
})

function isAgentComplete(agent: AgentEntry): boolean {
  if (agent.source === 'EXTERNAL') {
    return !!agent.displayName?.trim() && !!agent.agentCardUrl?.trim()
  }
  return !!agent.displayName?.trim()
    && !!agent.systemPrompt?.trim()
    && agent.systemPrompt.trim() !== PLACEHOLDER_PROMPT
}

function kindLabel(kind: string | undefined): string {
  switch (kind) {
    case 'chat':
      return '对话'
    case 'task':
      return '任务'
    default:
      return ''
  }
}

// ---- View 菜单 ----
const viewMenuOptions: DropdownOption[] = [
  {
    label: '编辑',
    key: 'edit',
    icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
  },
  { type: 'divider', key: 'divider-delete' },
  {
    label: () => h('span', { class: 'more-menu-delete' }, '删除'),
    key: 'delete',
    icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
  },
]

// ---- 加载表单 ----
function loadEditForm(agent: AgentEntry) {
  if (agent.source === 'EXTERNAL') {
    const auth = parseAuthConfig(agent)
    externalEdit.value = {
      displayName: agent.displayName,
      description: agent.description ?? '',
      agentCardUrl: agent.agentCardUrl ?? '',
      authConfigType: auth.type,
      authConfigToken: auth.token,
      endpointOverride: agent.endpointOverride ?? '',
    }
    return
  }
  editForm.value = {
    displayName: agent.displayName,
    description: agent.description ?? '',
    systemPrompt: agent.systemPrompt,
    skillIds: [...(agent.skillIds ?? [])],
    toolIds: parseAgentToolIds(agent.toolsJson),
    kbScope: (agent.kbScope ?? []).length > 0 ? agent.kbScope![0] : '',
    dataScope: agent.dataScopeJson ?? '',
    permissionsHitl: parsePermissionsString(agent.permissionsJson, 'hitl', 'inherit'),
    permissionsSandboxWrite: parsePermissionsString(agent.permissionsJson, 'sandboxWriteMode', 'inherit'),
    modelName: parseModelConfigModel(agent.modelConfigJson),
    maxIters: String(agent.maxIters ?? 0),
    maxHandoffs: String(agent.maxHandoffs ?? 0),
    kind: agent.kind ?? 'all',
    bizScene: agent.bizScene ?? null,
  }
}

// ---- 数据刷新 ----
async function refreshPage() {
  loading.value = true
  try {
    const [list, skills, tools, catalog] = await Promise.all([
      listAgents(),
      listSkillCatalogIndex(),
      listToolCatalog(),
      fetchModelCatalog().catch(() => null),
    ])
    agents.value = list
    skillOptions.value = skills
    toolOptions.value = tools
    modelSelectOptions.value = catalog
      ? catalogEnabledModelOptions(catalog).map((o) => ({ label: o.label, value: o.value }))
      : []
    try {
      kbOptions.value = await listKbs(tenantId.value)
    } catch {
      kbOptions.value = []
    }
    if (selectedId.value && !list.some(e => e.id === selectedId.value)) {
      selectedId.value = null
      isEditing.value = false
    }
    const preferred = selectedId.value || readId()
    const tabList = activeTab.value === 'internal' ? internalAgents.value : externalAgents.value
    const targetId = preferred && tabList.some(e => e.id === preferred)
      ? preferred
      : (tabList[0]?.id ?? null)
    if (targetId) {
      selectAgent(targetId, true)
    } else {
      selectedId.value = null
      syncId(null)
    }
  } catch (e) {
    message.error('加载智能体列表失败')
    console.error(e)
  } finally {
    loading.value = false
  }
}

function selectAgent(id: string, force = false) {
  if (!force && isEditing.value && isFormDirty.value && id !== selectedId.value) {
    message.warning('请先保存修改')
    return
  }
  selectedId.value = id
  syncId(id)
  isEditing.value = false
  const agent = agents.value.find(e => e.id === id)
  if (!agent) return
  loadEditForm(agent)
}

function enterEditMode() {
  if (!selectedAgent.value) return
  isEditing.value = true
}

function handleViewMenuSelect(key: string | number) {
  if (key === 'edit') enterEditMode()
  else if (key === 'delete') showDeleteConfirm.value = true
}

function cancelEdit() {
  const agent = selectedAgent.value
  if (!agent) return
  loadEditForm(agent)
  isEditing.value = false
}

function handleEscape(e: KeyboardEvent) {
  if (e.key !== 'Escape' || !isEditing.value) return
  cancelEdit()
}

// ---- Tab 切换 ----
function handleTabChange(tab: 'internal' | 'external') {
  if (isEditing.value && isFormDirty.value) {
    message.warning('请先保存修改')
    return
  }
  activeTab.value = tab
  const tabList = tab === 'internal' ? internalAgents.value : externalAgents.value
  const target = tabList[0]?.id ?? null
  if (target) {
    selectAgent(target, true)
  } else {
    selectedId.value = null
    syncId(null)
  }
}

// ---- 创建 ----
function openCreateModal() {
  if (activeTab.value === 'internal') {
    internalCreateDraft.value = { id: '', displayName: '' }
    showInternalCreateModal.value = true
  } else {
    externalCreateDraft.value = { id: '', displayName: '', agentCardUrl: '' }
    cardPreFill.value = null
    showExternalCreateModal.value = true
  }
}

async function handleInternalCreateConfirm() {
  if (!canConfirmInternalCreate.value) return
  creating.value = true
  try {
    const id = internalIdTrimmed.value
    const displayName = internalNameTrimmed.value
    await createAgent(id, displayName, PLACEHOLDER_PROMPT, '', [], [], 'INTERNAL')
    await setAgentEnabled(id, false)
    message.success('已创建')
    showInternalCreateModal.value = false
    await refreshPage()
    selectAgent(id, true)
  } catch (e) {
    message.error('创建失败，请检查 ID 是否重复')
    console.error(e)
  } finally {
    creating.value = false
  }
}

async function fetchCardForCreate() {
  if (!externalUrlTrimmed.value) return
  fetchingCard.value = true
  try {
    cardPreFill.value = await fetchAgentCard(externalUrlTrimmed.value)
    if (cardPreFill.value.error) {
      message.warning(cardPreFill.value.error)
      cardPreFill.value = null
    } else if (cardPreFill.value.displayName && !externalCreateDraft.value.displayName) {
      externalCreateDraft.value.displayName = cardPreFill.value.displayName
    }
  } catch (e) {
    message.error('拉取 Agent Card 失败')
    console.error(e)
  } finally {
    fetchingCard.value = false
  }
}

async function fetchCardForEdit() {
  const url = externalEdit.value.agentCardUrl.trim()
  if (!url) return
  fetchingCard.value = true
  try {
    const pre = await fetchAgentCard(url)
    if (pre.error) {
      message.warning(pre.error)
    } else {
      if (pre.displayName && !externalEdit.value.displayName.trim()) {
        externalEdit.value.displayName = pre.displayName
      }
      if (pre.description && !externalEdit.value.description.trim()) {
        externalEdit.value.description = pre.description
      }
      if (pre.endpointUrl && !externalEdit.value.endpointOverride.trim()) {
        externalEdit.value.endpointOverride = pre.endpointUrl
      }
      message.success('已从 Agent Card 预填信息')
    }
  } catch (e) {
    message.error('拉取 Agent Card 失败')
    console.error(e)
  } finally {
    fetchingCard.value = false
  }
}

async function handleExternalCreateConfirm() {
  if (!canConfirmExternalCreate.value) return
  creating.value = true
  try {
    const id = externalIdTrimmed.value
    await createAgent(
      id,
      externalNameTrimmed.value,
      '',
      cardPreFill.value?.description ?? '',
      [],
      [],
      'EXTERNAL',
      externalUrlTrimmed.value,
      undefined,
      cardPreFill.value?.endpointUrl || undefined,
    )
    await setAgentEnabled(id, false)
    message.success('已创建')
    showExternalCreateModal.value = false
    externalCreateDraft.value = { id: '', displayName: '', agentCardUrl: '' }
    cardPreFill.value = null
    await refreshPage()
    selectAgent(id, true)
  } catch (e) {
    message.error('创建失败，请检查 ID 是否重复')
    console.error(e)
  } finally {
    creating.value = false
  }
}

// ---- 保存 ----
async function handleSave() {
  if (!selectedId.value) return
  if (!editFormComplete.value) {
    message.warning('请填写必要信息')
    return
  }
  saving.value = true
  try {
    if (isExternalSelected.value) {
      const authJson = makeAuthConfigJson(externalEdit.value.authConfigType, externalEdit.value.authConfigToken)
      await updateAgent(
        selectedId.value,
        externalEdit.value.displayName.trim(),
        '',
        externalEdit.value.description.trim(),
        [],
        [],
        {
          source: 'EXTERNAL',
          agentCardUrl: externalEdit.value.agentCardUrl.trim() || undefined,
          authConfigJson: authJson || undefined,
          endpointOverride: externalEdit.value.endpointOverride.trim() || undefined,
        },
      )
    } else {
      await updateAgent(
        selectedId.value,
        editForm.value.displayName.trim(),
        editForm.value.systemPrompt.trim(),
        editForm.value.description.trim(),
        editForm.value.skillIds,
        editForm.value.toolIds,
        {
          kbScope: editForm.value.kbScope ? [editForm.value.kbScope] : [],
          dataScopeJson: editForm.value.dataScope || undefined,
          permissionsJson: makePermissionsJson({
            permissionsHitl: editForm.value.permissionsHitl,
            permissionsSandboxWrite: editForm.value.permissionsSandboxWrite,
          }) || undefined,
          modelConfigJson: buildModelConfigJson(editForm.value.modelName),
          maxIters: Number(editForm.value.maxIters) || 0,
          maxHandoffs: Number(editForm.value.maxHandoffs) || 0,
          kind: editForm.value.kind,
          bizScene: editForm.value.bizScene ?? null,
        },
      )
    }
    message.success('已保存')
    isEditing.value = false
    await refreshPage()
    selectAgent(selectedId.value, true)
  } catch (e) {
    message.error('保存失败')
    console.error(e)
  } finally {
    saving.value = false
  }
}

// ---- 删除 ----
const showDeleteConfirm = ref(false)

async function handleDeleteConfirm() {
  if (!selectedId.value) return
  deleting.value = true
  const id = selectedId.value
  try {
    await deleteAgent(id)
    message.success('已删除')
    showDeleteConfirm.value = false
    selectedId.value = null
    syncId(null)
    isEditing.value = false
    await refreshPage()
  } catch (e) {
    message.error('删除失败')
    console.error(e)
  } finally {
    deleting.value = false
  }
}

// ---- 启用/停用 ----
async function handleToggleEnabled(agent: AgentEntry, enabled: boolean) {
  if (agent.id === selectedId.value && isEditing.value) {
    message.warning('请先保存修改')
    return
  }
  if (!isAgentComplete(agent)) {
    message.warning('请先补全必要信息并保存')
    return
  }
  try {
    await setAgentEnabled(agent.id, enabled)
    message.success(enabled ? '已启用' : '已停用')
    await refreshPage()
    if (selectedId.value === agent.id) {
      selectAgent(agent.id, true)
    }
  } catch (e) {
    message.error('切换启用状态失败')
    console.error(e)
  }
}

// ---- 生命周期 ----
watch(
  () => readId(),
  (id) => {
    if (!id || id === selectedId.value) return
    if (agents.value.some(e => e.id === id)) {
      selectAgent(id, true)
    }
  },
)

/** 业务场景 Lab active 条目（biz_scene 下拉选项；value=code、label=中文名） */
const activeBizScenes = ref<BizSceneEntry[]>([])

const bizSceneOptions = computed(() =>
  activeBizScenes.value.map(s => ({ label: s.displayName || s.bizScene, value: s.bizScene })),
)

onMounted(() => {
  window.addEventListener('keydown', handleEscape)
  void refreshPage()
  void listBizScenes()
    .then(scenes => { activeBizScenes.value = scenes.filter(s => s.status === 'active') })
    .catch(() => { activeBizScenes.value = [] })
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleEscape)
})
</script>

<template>
  <div class="agents-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>智能体管理</h2>
      </div>
      <NSpace :size="8">
        <NButton round secondary @click="openCreateModal">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="loading" @click="refreshPage">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <div class="agents-tabs">
      <NTabs v-model:value="activeTab" type="line" @update:value="(v: string) => handleTabChange(v as 'internal' | 'external')">
        <NTabPane name="internal" tab="内部智能体" />
        <NTabPane name="external" tab="外部智能体" />
      </NTabs>
    </div>

    <div class="agents-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">
            <NIcon :component="activeTab === 'internal' ? HardwareChipOutline : CloudOutline" :size="14" />
            <span style="margin-left:6px">{{ activeTab === 'internal' ? '内部' : '外部' }}智能体</span>
          </span>
          <NTag :bordered="false" size="tiny" round>{{ searchedAgents.length }}</NTag>
        </div>
        <div class="list-search">
          <NInput
            v-model:value="agentSearch"
            placeholder="搜索名称或 ID…"
            size="small"
            round
            clearable
            class="search-input"
            :disabled="loading"
          >
            <template #prefix>
              <NIcon :component="SearchOutline" :size="14" />
            </template>
          </NInput>
        </div>
        <NSpin :show="loading" size="small" class="list-spin">
          <div class="list-body">
            <div v-if="searchedAgents.length" class="agent-list">
              <button
                v-for="agent in searchedAgents"
                :key="agent.id"
                type="button"
                class="agent-row"
                :class="{ active: agent.id === selectedId }"
                @click="selectAgent(agent.id)"
              >
                <div class="agent-row-head">
                  <div class="agent-name-row">
                    <span class="agent-name">{{ agent.displayName }}</span>
                    <span v-if="agent.kind && agent.kind !== 'all'" class="agent-kind">
                      <NTag :bordered="false" size="tiny" class="meta-chip">{{ kindLabel(agent.kind) }}</NTag>
                    </span>
                  </div>
                  <NSwitch
                    v-if="isAgentComplete(agent)"
                    :value="agent.enabled"
                    size="small"
                    @click.stop
                    @update:value="(v: boolean) => handleToggleEnabled(agent, v)"
                  />
                  <span v-else class="agent-badge draft">草稿</span>
                </div>
                <span class="agent-id">{{ agent.id }}</span>
              </button>
            </div>
            <div v-else-if="!loading" class="empty-wrap">
              <NEmpty
                size="small"
                :description="searchedAgents.length && agentSearch.trim() ? '无匹配智能体' : '暂无智能体'"
              />
            </div>
          </div>
        </NSpin>
      </aside>

      <!-- ========= 内部智能体详情 ========= -->
      <main v-if="selectedAgent && !isExternalSelected" class="detail-panel">
        <div class="detail-toolbar">
          <div class="detail-toolbar-text">
            <h3 class="detail-heading">{{ selectedAgent.displayName }}</h3>
            <span class="detail-id">{{ selectedAgent.id }}</span>
          </div>
          <div class="detail-actions">
            <NDropdown
              v-if="!isEditing"
              trigger="click"
              size="small"
              :options="viewMenuOptions"
              :disabled="saving || deleting"
              @select="handleViewMenuSelect"
            >
              <NButton
                size="small"
                quaternary
                class="more-menu-btn"
                title="更多操作"
                aria-label="更多操作"
                :loading="deleting"
                :disabled="saving"
              >
                <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
              </NButton>
            </NDropdown>
            <NSpace v-else :size="8">
              <NButton size="small" round secondary :disabled="saving" @click="cancelEdit">取消</NButton>
              <NButton
                size="small"
                round
                type="primary"
                class="action-btn"
                :loading="saving"
                :disabled="!isFormDirty || !editFormComplete"
                @click="handleSave"
              >保存</NButton>
            </NSpace>
          </div>
        </div>

        <div class="detail-scroll">
          <NForm class="detail-form" label-placement="top" :show-feedback="false">
            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">基本信息</h4>
              </header>
              <NFormItem label="展示名" required>
                <NInput v-model:value="editForm.displayName" class="sun-field" :disabled="!isEditing" placeholder="制度智能体" />
              </NFormItem>
              <NFormItem label="描述">
                <NInput v-model:value="editForm.description" class="sun-field sun-field-grow" type="textarea" :disabled="!isEditing" :autosize="{ minRows: 2, maxRows: 10 }" placeholder="智能体职责说明（可选）" />
              </NFormItem>
            </section>

            <section class="form-section">
              <header class="form-section-head prompt-head">
                <h4 class="form-section-title">系统提示词</h4>
                <span class="prompt-count">{{ editForm.systemPrompt.length }} 字</span>
              </header>
              <NFormItem label="角色与输出要求" required>
                <NInput v-model:value="editForm.systemPrompt" class="sun-field sun-field-grow prompt-input" type="textarea" :disabled="!isEditing" :autosize="{ minRows: 8, maxRows: 28 }" placeholder="定义智能体角色、分析范围与输出格式" />
              </NFormItem>
            </section>

            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">能力配置</h4>
              </header>
              <div class="form-grid form-grid-config">
                <NFormItem label="会话形态">
                  <NSelect v-model:value="editForm.kind" class="sun-field" :disabled="!isEditing" :options="[{ label: '全部', value: 'all' },{ label: '对话', value: 'chat' },{ label: '任务', value: 'task' }]" :menu-props="{ class: 'agent-select-menu' }" />
                </NFormItem>
                <NFormItem label="业务场景">
                  <NSelect v-model:value="editForm.bizScene" class="sun-field" :disabled="!isEditing" :options="bizSceneOptions" :menu-props="{ class: 'agent-select-menu' }" clearable placeholder="不绑定" />
                </NFormItem>
                <NFormItem label="关联 Skill">
                  <NSelect v-model:value="editForm.skillIds" class="sun-field" multiple filterable :disabled="!isEditing" :options="skillSelectOptions" :menu-props="{ class: 'agent-select-menu' }" placeholder="可选 0~N 个 Skill" />
                </NFormItem>
                <NFormItem label="工具">
                  <NSelect v-model:value="editForm.toolIds" class="sun-field" multiple filterable :disabled="!isEditing" :options="toolSelectOptions" :menu-props="{ class: 'agent-select-menu' }" placeholder="可选 0~N 个工具" />
                </NFormItem>
              </div>
            </section>

            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">运行参数</h4>
              </header>
              <div class="form-grid form-grid-config">
                <NFormItem label="最大迭代轮次">
                  <NInput v-model:value="editForm.maxIters" class="sun-field" placeholder="0=使用默认" :disabled="!isEditing" />
                </NFormItem>
                <NFormItem label="最大委派次数">
                  <NInput v-model:value="editForm.maxHandoffs" class="sun-field" placeholder="0=不限" :disabled="!isEditing" />
                </NFormItem>
              </div>
            </section>

            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">高级配置</h4>
              </header>
              <div class="form-grid form-grid-config">
                <NFormItem label="HITL 模式">
                  <NSelect v-model:value="editForm.permissionsHitl" class="sun-field" :disabled="!isEditing" :options="[{ label: '继承', value: 'inherit' },{ label: '总是', value: 'always' },{ label: '从不', value: 'never' }]" :menu-props="{ class: 'agent-select-menu' }" />
                </NFormItem>
                <NFormItem label="沙盒写模式">
                  <NSelect v-model:value="editForm.permissionsSandboxWrite" class="sun-field" :disabled="!isEditing" :options="[{ label: '继承', value: 'inherit' },{ label: '总是', value: 'always' },{ label: '智能', value: 'smart' },{ label: '从不', value: 'never' }]" :menu-props="{ class: 'agent-select-menu' }" />
                </NFormItem>
              </div>
              <NFormItem label="知识库">
                <NSelect v-model:value="editForm.kbScope" class="sun-field" filterable clearable :disabled="!isEditing" :options="kbSelectOptions" :menu-props="{ class: 'agent-select-menu' }" placeholder="选择一个知识库（可选）" />
              </NFormItem>
              <NFormItem label="数据范围（JSON）">
                <NInput v-model:value="editForm.dataScope" class="sun-field prompt-input" type="textarea" :disabled="!isEditing" :autosize="{ minRows: 2, maxRows: 6 }" placeholder='{"departments": ["hr", "finance"]}' />
              </NFormItem>
              <NFormItem label="模型">
                <NSelect
                  v-model:value="editForm.modelName"
                  class="sun-field"
                  filterable
                  clearable
                  :disabled="!isEditing"
                  :options="modelSelectOptions"
                  :menu-props="{ class: 'agent-select-menu' }"
                  placeholder="默认"
                />
              </NFormItem>
            </section>
          </NForm>
        </div>
      </main>

      <!-- ========= 外部智能体详情 ========= -->
      <main v-else-if="selectedAgent && isExternalSelected" class="detail-panel">
        <div class="detail-toolbar">
          <div class="detail-toolbar-text">
            <h3 class="detail-heading">{{ selectedAgent.displayName }}</h3>
            <span class="detail-id">{{ selectedAgent.id }}</span>
          </div>
          <div class="detail-actions">
            <NDropdown
              v-if="!isEditing"
              trigger="click"
              size="small"
              :options="viewMenuOptions"
              :disabled="saving || deleting"
              @select="handleViewMenuSelect"
            >
              <NButton size="small" quaternary class="more-menu-btn" title="更多操作" aria-label="更多操作" :loading="deleting" :disabled="saving">
                <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
              </NButton>
            </NDropdown>
            <NSpace v-else :size="8">
              <NButton size="small" secondary round :loading="fetchingCard" :disabled="!externalEdit.agentCardUrl.trim() || saving" @click="fetchCardForEdit">
                <template #icon><NIcon :component="CloudDownloadOutline" :size="14" /></template>
                拉取
              </NButton>
              <NButton size="small" round secondary :disabled="saving" @click="cancelEdit">取消</NButton>
              <NButton
                size="small"
                round
                type="primary"
                class="action-btn"
                :loading="saving"
                :disabled="!isFormDirty || !editFormComplete"
                @click="handleSave"
              >保存</NButton>
            </NSpace>
          </div>
        </div>

        <div class="detail-scroll">
          <NForm class="detail-form" label-placement="top" :show-feedback="false">
            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">基本信息</h4>
              </header>
              <NFormItem label="展示名" required>
                <NInput v-model:value="externalEdit.displayName" class="sun-field" :disabled="!isEditing" placeholder="外部智能体名称" />
              </NFormItem>
              <NFormItem label="描述">
                <NInput v-model:value="externalEdit.description" class="sun-field sun-field-grow" type="textarea" :disabled="!isEditing" :autosize="{ minRows: 2, maxRows: 10 }" placeholder="外部智能体职责说明（可选）" />
              </NFormItem>
            </section>

            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">A2A 接入</h4>
              </header>
              <NFormItem label="Agent Card URL" required>
                <NInput v-model:value="externalEdit.agentCardUrl" class="sun-field" :disabled="!isEditing" placeholder="https://example.com/.well-known/agent-card.json" />
              </NFormItem>
              <NFormItem label="认证方式">
                <NSelect v-model:value="externalEdit.authConfigType" class="sun-field" :disabled="!isEditing" :options="[{ label: 'Bearer Token', value: 'bearer' },{ label: 'API Key', value: 'api-key' }]" :menu-props="{ class: 'agent-select-menu' }" />
              </NFormItem>
              <NFormItem :label="externalEdit.authConfigType === 'api-key' ? 'API Key' : 'Token'">
                <NInput v-model:value="externalEdit.authConfigToken" class="sun-field" type="password" show-password-on="click" :disabled="!isEditing" placeholder="填写认证凭据" />
              </NFormItem>
              <NFormItem label="端点覆盖（可选，不填则从 Agent Card 自动解析）">
                <NInput v-model:value="externalEdit.endpointOverride" class="sun-field" :disabled="!isEditing" placeholder="https://api.example.com/v1" />
              </NFormItem>
            </section>
          </NForm>
        </div>
      </main>

      <main v-else class="detail-panel detail-empty">
        <NEmpty description="选择左侧智能体，或新建智能体" />
      </main>
    </div>

    <!-- ========= 内部智能体创建弹窗 ========= -->
    <NModal v-model:show="showInternalCreateModal" preset="dialog" title="新建内部智能体" class="sunshine-dialog agents-create-dialog">
      <NForm class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem label="智能体 ID" required>
          <NInput v-model:value="internalCreateDraft.id" class="sun-field" placeholder="policy-agent" @keydown.enter="canConfirmInternalCreate && handleInternalCreateConfirm()" />
          <p v-if="internalIdInvalid" class="field-error">仅支持字母、数字、连字符与中文</p>
          <p v-else-if="internalIdDuplicate" class="field-error">该 ID 已存在</p>
        </NFormItem>
        <NFormItem label="展示名" required>
          <NInput v-model:value="internalCreateDraft.displayName" class="sun-field" placeholder="制度智能体" @keydown.enter="canConfirmInternalCreate && handleInternalCreateConfirm()" />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showInternalCreateModal = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="creating" :disabled="!canConfirmInternalCreate" @click="handleInternalCreateConfirm">创建</NButton>
      </template>
    </NModal>

    <!-- ========= 外部智能体创建弹窗 ========= -->
    <NModal v-model:show="showExternalCreateModal" preset="dialog" title="新建外部智能体" class="sunshine-dialog agents-create-dialog external">
      <NForm class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem label="智能体 ID" required>
          <NInput v-model:value="externalCreateDraft.id" class="sun-field" placeholder="external-finance-agent" />
          <p v-if="externalIdInvalid" class="field-error">仅支持字母、数字、连字符与中文</p>
          <p v-else-if="externalIdDuplicate" class="field-error">该 ID 已存在</p>
        </NFormItem>
        <NFormItem label="展示名" required>
          <NInput v-model:value="externalCreateDraft.displayName" class="sun-field" placeholder="外部财务智能体" />
        </NFormItem>
        <NFormItem label="Agent Card URL" required>
            <NInput v-model:value="externalCreateDraft.agentCardUrl" class="sun-field" placeholder="https://example.com/.well-known/agent-card.json" />
        </NFormItem>
        <div v-if="cardPreFill && !cardPreFill.error" class="card-prefill-info">
          <div class="card-prefill-title">预填信息</div>
          <div class="card-prefill-grid">
            <span class="card-prefill-label">名称</span><span class="card-prefill-value">{{ cardPreFill.displayName || '-' }}</span>
            <span class="card-prefill-label">描述</span><span class="card-prefill-value">{{ cardPreFill.description || '-' }}</span>
            <span class="card-prefill-label">版本</span><span class="card-prefill-value">{{ cardPreFill.version || '-' }}</span>
            <span class="card-prefill-label">端点</span><span class="card-prefill-value">{{ cardPreFill.endpointUrl || '-' }}</span>
            <span class="card-prefill-label">Skills</span><span class="card-prefill-value">{{ cardPreFill.skills?.join(', ') || '-' }}</span>
          </div>
        </div>
      </NForm>
      <template #action>
        <NButton size="small" secondary round :loading="fetchingCard" :disabled="!externalUrlTrimmed || creating" @click="fetchCardForCreate">
          <template #icon><NIcon :component="CloudDownloadOutline" :size="14" /></template>
          拉取
        </NButton>
        <NButton @click="showExternalCreateModal = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="creating" :disabled="!canConfirmExternalCreate" @click="handleExternalCreateConfirm">创建</NButton>
      </template>
    </NModal>

    <!-- ========= 删除弹窗 ========= -->
    <NModal v-model:show="showDeleteConfirm" preset="dialog" title="删除智能体" class="sunshine-dialog">
      <p>确定删除智能体「{{ selectedAgent?.id }}」（{{ selectedAgent?.displayName }}）？此操作不可恢复。</p>
      <template #action>
        <NButton @click="showDeleteConfirm = false">取消</NButton>
        <NButton type="error" :loading="deleting" @click="handleDeleteConfirm">删除</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.agents-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 16px;
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

.agents-tabs {
  flex-shrink: 0;
}

.agents-tabs :deep(.n-tabs-nav) {
  --n-tab-text-color: var(--sun-text-muted);
  --n-tab-text-color-active: var(--sun-text);
  --n-tab-text-color-hover: var(--sun-text);
  --n-bar-color: var(--sun-text);
}

.agents-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.list-panel,
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.list-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.list-panel .panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px 0;
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
  display: flex;
  align-items: center;
}

.list-search {
  padding: 10px 12px;
  flex-shrink: 0;
}

.search-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
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

.detail-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.more-menu-btn {
  padding: 0 6px;
}

:deep(.more-menu-delete) {
  color: var(--n-color-error);
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
  letter-spacing: -0.02em;
  color: var(--sun-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.detail-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
  line-height: 1.4;
}

.detail-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-form :deep(.n-form-item) {
  margin-bottom: 0;
}

.detail-form :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding-bottom: 8px;
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 2px;
  border-bottom: 1px solid var(--sun-border);
}

.form-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
  letter-spacing: -0.01em;
}

.prompt-head {
  align-items: baseline;
}

.prompt-count {
  font-size: 12px;
  color: var(--sun-text-muted);
  flex-shrink: 0;
}

.detail-form :deep(.n-input),
.modal-form :deep(.n-input),
.prompt-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.detail-form :deep(.n-input),
.modal-form :deep(.n-input),
.detail-form :deep(.n-input-wrapper),
.modal-form :deep(.n-input-wrapper) {
  border-radius: var(--radius-md);
}

.prompt-input :deep(.n-input__textarea-el) {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.6;
  font-family: inherit;
}

.detail-form :deep(.n-base-selection),
.modal-form :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
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
  min-height: 38px;
}

.detail-form :deep(.n-base-selection-tags .n-tag) {
  --n-color: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
}

.form-grid {
  display: grid;
  gap: 16px 24px;
}

.form-grid-config {
  grid-template-columns: 1fr 1fr;
}

/* Card prefill info */
.card-prefill-info {
  margin-top: 8px;
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.card-prefill-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-muted);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.card-prefill-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 12px;
  font-size: 12px;
}

.card-prefill-label {
  color: var(--sun-text-muted);
}

.card-prefill-value {
  color: var(--sun-text);
  word-break: break-all;
}

/* Agent list */
.agent-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-row {
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

.agent-row:hover {
  border-color: var(--sun-border-light);
}

.agent-row.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.agent-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.agent-name-row {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.agent-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-kind {
  flex-shrink: 0;
}

.meta-chip {
  --n-color: color-mix(in srgb, var(--sun-text) 8%, var(--sun-black)) !important;
  --n-text-color: var(--sun-text-secondary) !important;
  --n-border: none !important;
  background: color-mix(in srgb, var(--sun-text) 8%, var(--sun-black)) !important;
}

.agent-id {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.agent-badge {
  flex-shrink: 0;
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  color: var(--sun-text-muted);
}

.agent-badge.draft {
  opacity: 0.85;
}

/* Modal：项间距由 global.css .sunshine-dialog 统一处理 */
.modal-form :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  font-weight: 500;
}

.field-error {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--n-color-error, #e88080);
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}

@media (max-width: 960px) {
  .agents-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }

  .form-grid-config {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
.agent-select-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
  --n-option-color-active: transparent !important;
  --n-option-color-active-pending: var(--sun-row-hover) !important;
  --n-option-color-pending: var(--sun-row-hover) !important;
  --n-option-text-color: var(--sun-text) !important;
  --n-option-text-color-active: var(--sun-text) !important;
  --n-option-check-color: var(--sun-text) !important;
  background: var(--sun-black) !important;
  border: 1px solid var(--sun-border) !important;
  box-shadow: var(--shadow-elevated) !important;
}

.agents-create-dialog.n-dialog {
  max-width: 540px !important;
}

.agents-create-dialog.external.n-dialog {
  max-width: 660px !important;
}
</style>
