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
  NTag,
  useMessage,
} from 'naive-ui'
import {
  AddOutline,
  CreateOutline,
  EllipsisHorizontal,
  RefreshOutline,
  SearchOutline,
  TrashOutline,
} from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import {
  createAgent,
  deleteAgent,
  listAgents,
  setAgentEnabled,
  updateAgent,
  type AgentEntry,
} from '../api/agents'
import { listSkillCatalogIndex, type SkillCatalogIndexEntry } from '../api/skills'
import { listToolCatalog, type ToolCatalogEntry } from '../api/tools'
import { useAgentsRouteState } from '../composables/useAgentsRouteState'

const PLACEHOLDER_PROMPT = '待补充系统提示词'
const AGENT_ID_PATTERN = /^[\w\u4e00-\u9fff-]+$/

const message = useMessage()
const { readId, syncId } = useAgentsRouteState()
const loading = ref(false)
const saving = ref(false)
const creating = ref(false)
const deleting = ref(false)
const isEditing = ref(false)
const agents = ref<AgentEntry[]>([])
const skillOptions = ref<SkillCatalogIndexEntry[]>([])
const toolOptions = ref<ToolCatalogEntry[]>([])
const selectedId = ref<string | null>(readId())
const showCreateModal = ref(false)
const showDeleteConfirm = ref(false)

const createDraft = ref({ id: '', displayName: '' })

const editForm = ref({
  displayName: '',
  description: '',
  systemPrompt: '',
  skillIds: [] as string[],
  toolIds: [] as string[],
  kbScopeText: '',
  dataScope: '',
  permissionsHitl: 'inherit' as string,
  permissionsSandboxWrite: 'inherit' as string,
  modelConfig: '',
  maxIters: 0 as number,
  maxHandoffs: 0 as number,
})

const selectedAgent = computed(() =>
  agents.value.find(e => e.id === selectedId.value) ?? null,
)

const agentSearch = ref('')
const filteredAgents = computed(() => {
  const q = agentSearch.value.trim().toLowerCase()
  if (!q) return agents.value
  return agents.value.filter(
    e =>
      e.id.toLowerCase().includes(q)
      || (e.displayName ?? '').toLowerCase().includes(q)
      || (e.description ?? '').toLowerCase().includes(q),
  )
})

const skillSelectOptions = computed(() =>
  skillOptions.value.map(s => ({ label: `${s.displayName} (${s.id})`, value: s.id })),
)

const toolSelectOptions = computed(() =>
  toolOptions.value
    .filter(t => t.enabled)
    .map(t => ({
      label: `${t.displayName || t.id} (${t.id})`,
      value: t.id,
    })),
)

const enabledToolIds = computed(() =>
  toolOptions.value.filter(t => t.enabled).map(t => t.id),
)

function parseAgentToolIds(toolsJson: string | undefined | null): string[] {
  if (!toolsJson?.trim()) return []
  try {
    const parsed = JSON.parse(toolsJson) as unknown
    if (!Array.isArray(parsed)) return []
    const ids = parsed.map(x => String(x).trim()).filter(Boolean)
    if (ids.length === 1 && ids[0] === '*') {
      return [...enabledToolIds.value]
    }
    return ids.filter(id => id !== '*')
  } catch {
    return []
  }
}

const createIdTrimmed = computed(() => createDraft.value.id.trim())
const createNameTrimmed = computed(() => createDraft.value.displayName.trim())

const createIdDuplicate = computed(() =>
  createIdTrimmed.value.length > 0
  && agents.value.some(e => e.id === createIdTrimmed.value),
)

const createIdInvalid = computed(() =>
  createIdTrimmed.value.length > 0 && !AGENT_ID_PATTERN.test(createIdTrimmed.value),
)

const canConfirmCreate = computed(() =>
  createIdTrimmed.value.length > 0
  && createNameTrimmed.value.length > 0
  && !createIdDuplicate.value
  && !createIdInvalid.value,
)

const editFormComplete = computed(() =>
  !!editForm.value.displayName.trim()
  && !!editForm.value.systemPrompt.trim()
  && editForm.value.systemPrompt.trim() !== PLACEHOLDER_PROMPT,
)

const systemPromptLength = computed(() => editForm.value.systemPrompt.length)

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

const isFormDirty = computed(() => {
  const agent = selectedAgent.value
  if (!agent) return false
  return editForm.value.displayName !== agent.displayName
    || editForm.value.description !== (agent.description ?? '')
    || editForm.value.systemPrompt !== agent.systemPrompt
    || JSON.stringify([...editForm.value.skillIds].sort())
      !== JSON.stringify([...(agent.skillIds ?? [])].sort())
    || JSON.stringify([...editForm.value.toolIds].sort())
      !== JSON.stringify([...parseAgentToolIds(agent.toolsJson)].sort())
    || editForm.value.kbScopeText !== (agent.kbScope ?? []).join('\n')
    || editForm.value.dataScope !== (agent.dataScopeJson ?? '')
    || parsePermissionsString(agent.permissionsJson, 'hitl', 'inherit') !== editForm.value.permissionsHitl
    || parsePermissionsString(agent.permissionsJson, 'sandboxWriteMode', 'inherit') !== editForm.value.permissionsSandboxWrite
    || editForm.value.modelConfig !== (agent.modelConfigJson ?? '')
    || editForm.value.maxIters !== (agent.maxIters ?? 0)
    || editForm.value.maxHandoffs !== (agent.maxHandoffs ?? 0)
})

function isAgentComplete(agent: AgentEntry): boolean {
  return !!agent.displayName?.trim()
    && !!agent.systemPrompt?.trim()
    && agent.systemPrompt.trim() !== PLACEHOLDER_PROMPT
}

function loadEditForm(agent: AgentEntry) {
  editForm.value = {
    displayName: agent.displayName,
    description: agent.description ?? '',
    systemPrompt: agent.systemPrompt,
    skillIds: [...(agent.skillIds ?? [])],
    toolIds: parseAgentToolIds(agent.toolsJson),
    kbScopeText: (agent.kbScope ?? []).join('\n'),
    dataScope: agent.dataScopeJson ?? '',
    permissionsHitl: parsePermissionsString(agent.permissionsJson, 'hitl', 'inherit'),
    permissionsSandboxWrite: parsePermissionsString(agent.permissionsJson, 'sandboxWriteMode', 'inherit'),
    modelConfig: agent.modelConfigJson ?? '',
    maxIters: agent.maxIters ?? 0,
    maxHandoffs: agent.maxHandoffs ?? 0,
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

function makePermissionsJson(): string {
  const obj: Record<string, string> = {}
  if (editForm.value.permissionsHitl !== 'inherit') obj.hitl = editForm.value.permissionsHitl
  if (editForm.value.permissionsSandboxWrite !== 'inherit') obj.sandboxWriteMode = editForm.value.permissionsSandboxWrite
  if (Object.keys(obj).length === 0) return ''
  return JSON.stringify(obj)
}

async function refreshPage() {
  loading.value = true
  try {
    const [list, skills, tools] = await Promise.all([
      listAgents(),
      listSkillCatalogIndex(),
      listToolCatalog(),
    ])
    agents.value = list
    skillOptions.value = skills
    toolOptions.value = tools
    if (selectedId.value && !list.some(e => e.id === selectedId.value)) {
      selectedId.value = null
      isEditing.value = false
    }
    const preferred = selectedId.value || readId()
    const targetId = preferred && list.some(e => e.id === preferred)
      ? preferred
      : (list[0]?.id ?? null)
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

function openCreateModal() {
  createDraft.value = { id: '', displayName: '' }
  showCreateModal.value = true
}

async function handleCreateConfirm() {
  if (!canConfirmCreate.value) return
  creating.value = true
  try {
    const id = createIdTrimmed.value
    const displayName = createNameTrimmed.value
    await createAgent(id, displayName, PLACEHOLDER_PROMPT, '', [])
    await setAgentEnabled(id, false)
    message.success('已创建')
    showCreateModal.value = false
    createDraft.value = { id: '', displayName: '' }
    await refreshPage()
    selectAgent(id, true)
  } catch (e) {
    message.error('创建失败，请检查 ID 是否重复')
    console.error(e)
  } finally {
    creating.value = false
  }
}

async function handleSave() {
  if (!selectedId.value) return
  if (!editFormComplete.value) {
    message.warning('请填写展示名与系统提示词')
    return
  }
  saving.value = true
  try {
    await updateAgent(
      selectedId.value,
      editForm.value.displayName.trim(),
      editForm.value.systemPrompt.trim(),
      editForm.value.description.trim(),
      editForm.value.skillIds,
      editForm.value.toolIds,
      {
        kbScope: editForm.value.kbScopeText ? editForm.value.kbScopeText.split('\n').map(s => s.trim()).filter(Boolean) : [],
        dataScopeJson: editForm.value.dataScope || undefined,
        permissionsJson: makePermissionsJson() || undefined,
        modelConfigJson: editForm.value.modelConfig || undefined,
        maxIters: Number(editForm.value.maxIters) || 0,
        maxHandoffs: Number(editForm.value.maxHandoffs) || 0,
      },
    )
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

async function handleToggleEnabled(agent: AgentEntry, enabled: boolean) {
  if (agent.id === selectedId.value && isEditing.value) {
    message.warning('请先保存修改')
    return
  }
  if (!isAgentComplete(agent)) {
    message.warning('请先补全展示名与系统提示词并保存')
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

watch(
  () => readId(),
  (id) => {
    if (!id || id === selectedId.value) return
    if (agents.value.some(e => e.id === id)) {
      selectAgent(id, true)
    }
  },
)

onMounted(() => {
  window.addEventListener('keydown', handleEscape)
  void refreshPage()
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

    <div class="agents-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">列表</span>
          <NTag :bordered="false" size="tiny" round>{{ filteredAgents.length }}</NTag>
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
            <div v-if="filteredAgents.length" class="agent-list">
              <button
                v-for="agent in filteredAgents"
                :key="agent.id"
                type="button"
                class="agent-row"
                :class="{ active: agent.id === selectedId }"
                @click="selectAgent(agent.id)"
              >
                <div class="agent-row-head">
                  <span class="agent-name">{{ agent.displayName }}</span>
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
                :description="agents.length && agentSearch.trim() ? '无匹配智能体' : '暂无智能体'"
              />
            </div>
          </div>
        </NSpin>
      </aside>

      <main v-if="selectedAgent" class="detail-panel">
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
              <NButton
                size="small"
                round
                secondary
                :disabled="saving"
                @click="cancelEdit"
              >
                取消
              </NButton>
              <NButton
                size="small"
                round
                type="primary"
                class="action-btn"
                :loading="saving"
                :disabled="!isFormDirty || !editFormComplete"
                @click="handleSave"
              >
                保存
              </NButton>
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
                <NInput
                  v-model:value="editForm.displayName"
                  class="sun-field"
                  :disabled="!isEditing"
                  placeholder="制度智能体"
                />
              </NFormItem>
              <NFormItem label="描述">
                <NInput
                  v-model:value="editForm.description"
                  class="sun-field sun-field-grow"
                  type="textarea"
                  :disabled="!isEditing"
                  :autosize="{ minRows: 2, maxRows: 10 }"
                  placeholder="智能体职责说明（可选）"
                />
              </NFormItem>
            </section>

            <section class="form-section">
              <header class="form-section-head prompt-head">
                <h4 class="form-section-title">系统提示词</h4>
                <span class="prompt-count">{{ systemPromptLength }} 字</span>
              </header>
              <NFormItem label="角色与输出要求" required>
                <NInput
                  v-model:value="editForm.systemPrompt"
                  class="sun-field sun-field-grow prompt-input"
                  type="textarea"
                  :disabled="!isEditing"
                  :autosize="{ minRows: 8, maxRows: 28 }"
                  placeholder="定义智能体角色、分析范围与输出格式"
                />
              </NFormItem>
            </section>

            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">能力配置</h4>
              </header>
              <div class="form-grid form-grid-config">
                <NFormItem label="关联 Skill">
                  <NSelect
                    v-model:value="editForm.skillIds"
                    class="sun-field"
                    multiple
                    filterable
                    :disabled="!isEditing"
                    :options="skillSelectOptions"
                    :menu-props="{ class: 'agent-select-menu' }"
                    placeholder="可选 0~N 个 Skill"
                  />
                </NFormItem>
                <NFormItem label="工具">
                  <NSelect
                    v-model:value="editForm.toolIds"
                    class="sun-field"
                    multiple
                    filterable
                    :disabled="!isEditing"
                    :options="toolSelectOptions"
                    :menu-props="{ class: 'agent-select-menu' }"
                    placeholder="可选 0~N 个工具"
                  />
                </NFormItem>
              </div>
            </section>

            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">运行参数</h4>
              </header>
              <div class="form-grid form-grid-config">
                <NFormItem label="最大迭代轮次">
                  <NInput
                    v-model:value="editForm.maxIters"
                    class="sun-field"
                    placeholder="0=使用默认"
                    :disabled="!isEditing"
                  />
                </NFormItem>
                <NFormItem label="最大委派次数">
                  <NInput
                    v-model:value="editForm.maxHandoffs"
                    class="sun-field"
                    placeholder="0=不限"
                    :disabled="!isEditing"
                  />
                </NFormItem>
              </div>
            </section>

            <section class="form-section">
              <header class="form-section-head">
                <h4 class="form-section-title">高级配置</h4>
              </header>
              <div class="form-grid form-grid-config">
                <NFormItem label="HITL 模式">
                  <NSelect
                    v-model:value="editForm.permissionsHitl"
                    class="sun-field"
                    :disabled="!isEditing"
                    :options="[
                      { label: '继承', value: 'inherit' },
                      { label: '总是', value: 'always' },
                      { label: '从不', value: 'never' },
                    ]"
                    :menu-props="{ class: 'agent-select-menu' }"
                  />
                </NFormItem>
                <NFormItem label="沙盒写模式">
                  <NSelect
                    v-model:value="editForm.permissionsSandboxWrite"
                    class="sun-field"
                    :disabled="!isEditing"
                    :options="[
                      { label: '继承', value: 'inherit' },
                      { label: '总是', value: 'always' },
                      { label: '智能', value: 'smart' },
                      { label: '从不', value: 'never' },
                    ]"
                    :menu-props="{ class: 'agent-select-menu' }"
                  />
                </NFormItem>
              </div>
              <NFormItem label="知识库范围（每行一个 ID，* 代表所有）">
                <NInput
                  v-model:value="editForm.kbScopeText"
                  class="sun-field sun-field-grow"
                  type="textarea"
                  :disabled="!isEditing"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  placeholder="default"
                />
              </NFormItem>
              <NFormItem label="数据范围（JSON）">
                <NInput
                  v-model:value="editForm.dataScope"
                  class="sun-field prompt-input"
                  type="textarea"
                  :disabled="!isEditing"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  placeholder='{"departments": ["hr", "finance"]}'
                />
              </NFormItem>
              <NFormItem label="模型配置（JSON，覆盖默认模型）">
                <NInput
                  v-model:value="editForm.modelConfig"
                  class="sun-field prompt-input"
                  type="textarea"
                  :disabled="!isEditing"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  placeholder='{"modelName": "gpt-4o", "modelBaseUrl": ""}'
                />
              </NFormItem>
            </section>
          </NForm>
        </div>
      </main>

      <main v-else class="detail-panel detail-empty">
        <NEmpty description="选择左侧智能体，或新建智能体" />
      </main>
    </div>

    <NModal
      v-model:show="showCreateModal"
      preset="dialog"
      title="新建智能体"
      class="sunshine-dialog"
    >
      <NForm class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem label="智能体 ID" required>
          <NInput
            v-model:value="createDraft.id"
            class="sun-field"
            placeholder="policy-agent"
            @keydown.enter="canConfirmCreate && handleCreateConfirm()"
          />
          <p v-if="createIdInvalid" class="field-error">仅支持字母、数字、连字符与中文</p>
          <p v-else-if="createIdDuplicate" class="field-error">该 ID 已存在</p>
        </NFormItem>
        <NFormItem label="展示名" required>
          <NInput
            v-model:value="createDraft.displayName"
            class="sun-field"
            placeholder="制度智能体"
            @keydown.enter="canConfirmCreate && handleCreateConfirm()"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showCreateModal = false">取消</NButton>
        <NButton
          type="primary"
          class="action-btn"
          :loading="creating"
          :disabled="!canConfirmCreate"
          @click="handleCreateConfirm"
        >
          创建
        </NButton>
      </template>
    </NModal>

    <NModal
      v-model:show="showDeleteConfirm"
      preset="dialog"
      title="删除智能体"
      class="sunshine-dialog"
    >
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

.agent-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.modal-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.modal-form :deep(.n-form-item) {
  margin-bottom: 12px;
}

.modal-form :deep(.n-form-item:last-child) {
  margin-bottom: 0;
}

.modal-form :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding-bottom: 8px;
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
</style>
