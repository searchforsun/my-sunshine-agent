<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NCheckbox,
  NEmpty,
  NIcon,
  NInput,
  NModal,
  NSpace,
  NSpin,
  NTag,
  useMessage,
} from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import {
  addToolSetMembers,
  fetchToolSetPicker,
  type ToolSetKindPath,
  type ToolSetMemberAddItem,
  type ToolSetPickerGroup,
} from '../../api/tools'

const props = defineProps<{
  show: boolean
  kind: ToolSetKindPath
  tenantId?: string
  allowCritical: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  added: []
}>()

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const filterQuery = ref('')
const groups = ref<ToolSetPickerGroup[]>([])
const selected = ref<Map<string, { critical: boolean }>>(new Map())

const modalTitle = computed(() =>
  props.kind === 'react-default' ? '添加到 ReAct 工具集' : '添加到 Planner Workflow 工具集',
)

const allTools = computed(() =>
  groups.value.flatMap(g => g.tools),
)

const selectedCount = computed(() => selected.value.size)

const allSelected = computed(() =>
  allTools.value.length > 0 && allTools.value.every(t => selected.value.has(t.toolId)),
)

const someSelected = computed(() =>
  allTools.value.some(t => selected.value.has(t.toolId)),
)

async function loadPicker() {
  loading.value = true
  try {
    const tenant = props.tenantId === 'default' ? undefined : props.tenantId
    const resp = await fetchToolSetPicker(props.kind, tenant, filterQuery.value)
    groups.value = resp.groups ?? []
  } catch (e) {
    message.error('加载候选工具失败')
    console.error(e)
  } finally {
    loading.value = false
  }
}

function isChecked(toolId: string): boolean {
  return selected.value.has(toolId)
}

function isCritical(toolId: string): boolean {
  return selected.value.get(toolId)?.critical ?? false
}

function toggleTool(toolId: string, checked: boolean) {
  const next = new Map(selected.value)
  if (checked) next.set(toolId, { critical: false })
  else next.delete(toolId)
  selected.value = next
}

function toggleCritical(toolId: string, critical: boolean) {
  const next = new Map(selected.value)
  const cur = next.get(toolId)
  if (cur) next.set(toolId, { ...cur, critical })
  selected.value = next
}

function setToolsChecked(toolIds: string[], checked: boolean) {
  const next = new Map(selected.value)
  for (const toolId of toolIds) {
    if (checked) next.set(toolId, next.get(toolId) ?? { critical: false })
    else next.delete(toolId)
  }
  selected.value = next
}

function toggleSelectAll(checked: boolean) {
  setToolsChecked(allTools.value.map(t => t.toolId), checked)
}

function groupState(group: ToolSetPickerGroup): 'all' | 'none' | 'partial' {
  const ids = group.tools.map(t => t.toolId)
  const hit = ids.filter(id => selected.value.has(id)).length
  if (hit === 0) return 'none'
  if (hit === ids.length) return 'all'
  return 'partial'
}

function toggleGroup(group: ToolSetPickerGroup, checked: boolean) {
  setToolsChecked(group.tools.map(t => t.toolId), checked)
}

async function confirmAdd() {
  if (selectedCount.value === 0) return
  saving.value = true
  try {
    const tenant = props.tenantId === 'default' ? undefined : props.tenantId
    const items: ToolSetMemberAddItem[] = [...selected.value.entries()].map(([toolId, meta]) => ({
      toolId,
      critical: props.allowCritical ? meta.critical : false,
    }))
    const result = await addToolSetMembers(props.kind, items, tenant)
    if (result.added.length > 0) {
      message.success(`已添加 ${result.added.length} 个工具`)
      emit('added')
      emit('update:show', false)
      selected.value = new Map()
    }
    if (result.rejected.length > 0) {
      message.warning(`${result.rejected.length} 个工具未添加（未启用或不存在）`)
    }
  } catch (e) {
    message.error('添加工具失败')
    console.error(e)
  } finally {
    saving.value = false
  }
}

watch(() => props.show, (open) => {
  if (open) {
    selected.value = new Map()
    filterQuery.value = ''
    void loadPicker()
  }
})

let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(filterQuery, () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => { void loadPicker() }, 300)
})
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="modalTitle"
    class="sunshine-dialog toolset-add-dialog"
    :style="{ width: 'min(960px, 92vw)' }"
    :bordered="false"
    size="huge"
    @update:show="emit('update:show', $event)"
  >
    <div class="toolset-add-body">
      <div class="toolset-add-toolbar">
        <NInput
          v-model:value="filterQuery"
          size="medium"
          clearable
          class="toolset-add-search sun-field"
          placeholder="搜索工具、ID 或 SDK/MCP 名称…"
        >
          <template #prefix>
            <NIcon :component="SearchOutline" :size="16" />
          </template>
        </NInput>
        <div v-if="allTools.length" class="toolset-add-bulk">
          <NCheckbox
            :checked="allSelected"
            :indeterminate="someSelected && !allSelected"
            @update:checked="toggleSelectAll"
          >
            全选
          </NCheckbox>
          <NCheckbox
            :checked="selectedCount === 0"
            @update:checked="(v: boolean) => { if (selectedCount > 0) toggleSelectAll(false) }"
          >
            全不选
          </NCheckbox>
          <span class="toolset-add-count">已选 {{ selectedCount }} / {{ allTools.length }}</span>
        </div>
      </div>

      <NSpin :show="loading" class="toolset-add-spin">
        <div v-if="groups.length" class="toolset-add-groups">
          <section
            v-for="group in groups"
            :key="group.source + group.sourceRef"
            class="toolset-add-group"
          >
            <header class="toolset-add-group-head">
              <NCheckbox
                :checked="groupState(group) === 'all'"
                :indeterminate="groupState(group) === 'partial'"
                @update:checked="(v: boolean) => toggleGroup(group, v)"
              >
                <span class="toolset-add-group-title">{{ group.title }}</span>
              </NCheckbox>
              <NTag :bordered="false" size="small" round>{{ group.tools.length }}</NTag>
            </header>
            <div class="toolset-add-tools">
              <div v-for="tool in group.tools" :key="tool.toolId" class="toolset-add-row">
                <NCheckbox
                  class="toolset-add-check"
                  :checked="isChecked(tool.toolId)"
                  @update:checked="(v: boolean) => toggleTool(tool.toolId, v)"
                />
                <div class="toolset-add-main">
                  <div class="toolset-add-name-row">
                    <span class="toolset-add-name">{{ tool.displayName }}</span>
                    <NTag
                      :bordered="false"
                      size="tiny"
                      :type="tool.sideEffect === 'write' ? 'warning' : 'default'"
                    >
                      {{ tool.sideEffect === 'write' ? '写' : '读' }}
                    </NTag>
                  </div>
                  <div class="toolset-add-id">{{ tool.toolId }}</div>
                </div>
                <div v-if="allowCritical" class="toolset-add-critical">
                  <NCheckbox
                    :disabled="!isChecked(tool.toolId)"
                    :checked="isCritical(tool.toolId)"
                    @update:checked="(v: boolean) => toggleCritical(tool.toolId, v)"
                  >
                    关键
                  </NCheckbox>
                </div>
              </div>
            </div>
          </section>
        </div>
        <NEmpty v-else class="toolset-add-empty" description="无可用工具（请先在 SDK/MCP 中启用）" />
      </NSpin>
    </div>

    <template #footer>
      <NSpace justify="end" align="center" :size="12">
        <span v-if="allTools.length" class="toolset-add-footer-count">已选 {{ selectedCount }} 项</span>
        <NButton size="medium" @click="emit('update:show', false)">取消</NButton>
        <NButton
          type="primary"
          size="medium"
          class="action-btn"
          :disabled="selectedCount === 0"
          :loading="saving"
          @click="confirmAdd"
        >
          确认添加
        </NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.toolset-add-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 480px;
}

.toolset-add-toolbar {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolset-add-search {
  width: 100%;
}

.toolset-add-bulk {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 20px;
  padding: 12px 16px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.toolset-add-count {
  margin-left: auto;
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.toolset-add-spin {
  flex: 1;
  min-height: 0;
}

.toolset-add-spin :deep(.n-spin-container) {
  height: 100%;
}

.toolset-add-groups {
  display: flex;
  flex-direction: column;
  gap: 20px;
  overflow-y: auto;
  max-height: min(56vh, 520px);
  padding-right: 4px;
}

.toolset-add-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.toolset-add-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 4px;
}

.toolset-add-group-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.toolset-add-tools {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.toolset-add-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.toolset-add-check {
  flex-shrink: 0;
}

.toolset-add-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.toolset-add-name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.toolset-add-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.toolset-add-id {
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
  word-break: break-all;
}

.toolset-add-critical {
  flex-shrink: 0;
  padding-left: 8px;
}

.toolset-add-empty {
  padding: 48px 0;
}

.toolset-add-footer-count {
  font-size: 13px;
  color: var(--sun-text-secondary);
  margin-right: 8px;
}

.toolset-add-dialog :deep(.n-card-header) {
  padding: 20px 24px 12px;
}

.toolset-add-dialog :deep(.n-card__content) {
  padding: 8px 24px 16px;
}

.toolset-add-dialog :deep(.n-card__footer) {
  padding: 16px 24px 20px;
  border-top: 1px solid var(--sun-border);
}
</style>
