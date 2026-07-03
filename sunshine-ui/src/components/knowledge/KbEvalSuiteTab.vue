<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
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
  NSpin,
  NTag,
  NText,
  useMessage,
} from 'naive-ui'
import { CreateOutline, DocumentTextOutline, EllipsisHorizontal, TrashOutline } from '@vicons/ionicons5'
import { registerHljsLanguages } from '../../utils/markdown/registerHljsLanguages'
import {
  createEvalSuite,
  deleteEvalSuite,
  getEvalSuite,
  listEvalSuites,
  mutateEvalSuiteQuery,
  updateEvalSuite,
  type EvalSuiteDetail,
  type EvalSuiteItemView,
  type EvalSuiteSummary,
  type KbDocument,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import {
  BUILTIN_SUITE_KEYS,
  DEFAULT_EVAL_GATES,
  DEFAULT_EVAL_MIN_SCORE,
  DEFAULT_EVAL_CATEGORY,
  defaultEvalSuiteConfig,
  EVAL_CATEGORY_OPTIONS,
  EVAL_TOP_K_OPTIONS,
  formatEvalCategory,
  normalizeEvalSuiteConfig,
  serializeEvalSuiteConfig,
  suiteKeyFormatError,
  type EvalSuiteConfig,
} from '../../utils/evalConstants'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
  kbDisplayName?: string
  selectedSuiteKey: string
  documents: KbDocument[]
  loadingDocs?: boolean
}>()

const emit = defineEmits<{
  'update:selectedSuiteKey': [value: string]
  suitesChanged: []
  refreshDocuments: []
}>()

const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const suites = ref<EvalSuiteSummary[]>([])
const detail = ref<EvalSuiteDetail | null>(null)
const search = ref('')
const detailView = ref<'items' | 'config' | 'script'>('items')
const items = ref<EvalSuiteItemView[]>([])
const editingKey = ref('')
const showCreate = ref(false)
const createForm = ref({ displayName: '', suiteKey: '', kind: 'standard' })
const showAddItem = ref(false)
const showRename = ref(false)
const showDeleteConfirm = ref(false)
const showDeleteItemConfirm = ref(false)
const deleteItemTarget = ref<EvalSuiteItemView | null>(null)
const deletingItem = ref(false)
const deleting = ref(false)
const renameForm = ref('')
const addForm = ref({ query: '', docIds: [] as string[], category: DEFAULT_EVAL_CATEGORY })
const configDraft = ref<EvalSuiteConfig>(defaultEvalSuiteConfig())
const hljs = registerHljsLanguages()

const isStandard = computed(() => detail.value?.kind === 'standard')
const isEditable = computed(() => detail.value != null && !detail.value.builtin)
const scriptPreviewHtml = computed(() => {
  const text = detail.value?.content ?? ''
  if (!text) return ''
  try {
    return hljs.highlight(text, { language: 'python' }).value
  } catch {
    return hljs.highlightAuto(text).value
  }
})

const gateDraft = computed(() => ({
  ...DEFAULT_EVAL_GATES,
  ...configDraft.value.gates,
}))

const suiteMoreOptions = computed(() => [
  {
    label: '重命名',
    key: 'rename',
    disabled: !isEditable.value,
    icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
  },
  {
    label: () => h('span', { class: 'more-menu-delete' }, '删除'),
    key: 'delete',
    disabled: !isEditable.value,
    icon: () => h(NIcon, { component: TrashOutline, size: 14, class: 'more-menu-delete' }),
  },
])

const filteredSuites = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return suites.value
  return suites.value.filter(
    (s) => s.displayName.toLowerCase().includes(q) || s.suiteKey.toLowerCase().includes(q),
  )
})

const docSelectOptions = computed(() =>
  props.documents.map((d) => ({ label: d.displayName, value: d.docId })),
)

const docDisplayNameMap = computed(() => {
  const map = new Map<string, string>()
  for (const d of props.documents) map.set(d.docId, d.displayName)
  return map
})

function formatRelevantDocs(docIds: string[]): string {
  if (!docIds.length) return '—'
  return docIds.map((id) => docDisplayNameMap.value.get(id) ?? id).join('、')
}

function applyDetail(loaded: EvalSuiteDetail) {
  detail.value = loaded
  items.value = loaded.items ?? []
  configDraft.value = normalizeEvalSuiteConfig(loaded.config)
}

function openCreate() {
  createForm.value = { displayName: '', suiteKey: '', kind: 'standard' }
  showCreate.value = true
}

function openAddItem() {
  if (!props.kbId) {
    message.warning('请先选择知识库')
    return
  }
  addForm.value = { query: '', docIds: [], category: DEFAULT_EVAL_CATEGORY }
  emit('refreshDocuments')
  showAddItem.value = true
}

async function loadSuites() {
  loading.value = true
  try {
    suites.value = await listEvalSuites(props.tenantId)
    if (suites.value.length === 0) {
      detail.value = null
      return
    }
    const key = editingKey.value || props.selectedSuiteKey
    const exists = suites.value.some((s) => s.suiteKey === key)
    const target = exists
      ? key
      : (suites.value.find((s) => s.suiteKey === 'sunshine-regression') ?? suites.value[0]).suiteKey
    editingKey.value = target
    if (target !== props.selectedSuiteKey) emit('update:selectedSuiteKey', target)
    await loadDetail(target)
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载评测集失败'))
  } finally {
    loading.value = false
  }
}

async function loadDetail(suiteKey: string) {
  try {
    const loaded = await getEvalSuite(props.tenantId, suiteKey)
    applyDetail(loaded)
    editingKey.value = suiteKey
    detailView.value = loaded.kind === 'python' ? 'script' : 'items'
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载详情失败'))
  }
}

function selectSuite(key: string) {
  if (key === editingKey.value) return
  void loadDetail(key)
}

function updateMinScore(value: number | null) {
  configDraft.value = {
    ...configDraft.value,
    minScore: value == null || Number.isNaN(value) ? DEFAULT_EVAL_MIN_SCORE : value,
  }
}

function updateTopK(value: number[] | null) {
  const nums = (value ?? [])
    .map((n) => Number(n))
    .filter((n) => Number.isFinite(n) && n > 0)
  configDraft.value = {
    ...configDraft.value,
    topK: nums.length ? [...new Set(nums)].sort((a, b) => a - b) : [3, 5, 10],
  }
}

function updateGateField(key: keyof typeof DEFAULT_EVAL_GATES, value: number | null) {
  const gates = { ...DEFAULT_EVAL_GATES, ...configDraft.value.gates }
  gates[key] = value == null || Number.isNaN(value) ? DEFAULT_EVAL_GATES[key]! : value
  configDraft.value = { ...configDraft.value, gates }
}

async function handleSaveConfig() {
  if (!detail.value || detail.value.builtin) return
  saving.value = true
  try {
    const saved = await updateEvalSuite(props.tenantId, detail.value.suiteKey, {
      config: serializeEvalSuiteConfig(configDraft.value),
    })
    applyDetail(saved)
    message.success('配置已保存')
    emit('suitesChanged')
  } catch (e) {
    message.error(friendlyErrorMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function handleAddItem() {
  if (!detail.value || detail.value.builtin) return
  if (!addForm.value.query.trim()) {
    message.warning('请填写问题')
    return
  }
  try {
    const saved = await mutateEvalSuiteQuery(props.tenantId, detail.value.suiteKey, {
      action: 'add',
      query: addForm.value.query.trim(),
      relevantDocIds: addForm.value.docIds,
      category: addForm.value.category,
    })
    applyDetail(saved)
    showAddItem.value = false
    message.success('已添加条目')
    emit('suitesChanged')
    await loadSuites()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '添加失败'))
  }
}

function requestDeleteItem(row: EvalSuiteItemView) {
  deleteItemTarget.value = row
  showDeleteItemConfirm.value = true
}

async function handleConfirmDeleteItem() {
  const target = deleteItemTarget.value
  if (!detail.value || detail.value.builtin || !target) return
  deletingItem.value = true
  try {
    const saved = await mutateEvalSuiteQuery(props.tenantId, detail.value.suiteKey, {
      action: 'delete',
      id: target.itemKey,
    })
    applyDetail(saved)
    showDeleteItemConfirm.value = false
    deleteItemTarget.value = null
    message.success('已删除条目')
    emit('suitesChanged')
    await loadSuites()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '删除失败'))
  } finally {
    deletingItem.value = false
  }
}

async function handleRename() {
  if (!detail.value || detail.value.builtin) return
  const name = renameForm.value.trim()
  if (!name) {
    message.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const saved = await updateEvalSuite(props.tenantId, detail.value.suiteKey, { displayName: name })
    applyDetail(saved)
    showRename.value = false
    message.success('已重命名')
    emit('suitesChanged')
    await loadSuites()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '重命名失败'))
  } finally {
    saving.value = false
  }
}

async function handleCreate() {
  const name = createForm.value.displayName.trim()
  const key = createForm.value.suiteKey.trim()
  const kind = createForm.value.kind
  if (!name) {
    message.warning('请填写名称')
    return
  }
  const formatErr = suiteKeyFormatError(key)
  if (formatErr) {
    message.warning(formatErr)
    return
  }
  if (suites.value.some((s) => s.suiteKey === key)) {
    message.warning('Key 已存在，请换一个')
    return
  }
  if ((BUILTIN_SUITE_KEYS as readonly string[]).includes(key)) {
    message.warning('该 Key 为内置保留名称')
    return
  }
  saving.value = true
  try {
    await createEvalSuite(props.tenantId, {
      suiteKey: key,
      displayName: name,
      description: name,
      kind,
      content: kind === 'python' ? '# Sunshine RAG Eval Runner\n' : undefined,
      config: kind === 'standard' ? serializeEvalSuiteConfig(defaultEvalSuiteConfig()) : undefined,
    })
    showCreate.value = false
    createForm.value = { displayName: '', suiteKey: '', kind: 'standard' }
    message.success('已创建')
    emit('update:selectedSuiteKey', key)
    emit('suitesChanged')
    await loadSuites()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '创建失败'))
  } finally {
    saving.value = false
  }
}

async function handleDeleteSuite() {
  if (!detail.value || detail.value.builtin) return
  const deletedKey = detail.value.suiteKey
  deleting.value = true
  try {
    await deleteEvalSuite(props.tenantId, deletedKey)
    showDeleteConfirm.value = false
    detail.value = null
    editingKey.value = ''
    message.success('已删除')
    emit('suitesChanged')
    if (props.selectedSuiteKey === deletedKey) {
      emit('update:selectedSuiteKey', 'sunshine-regression')
    }
    await loadSuites()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '删除失败'))
  } finally {
    deleting.value = false
  }
}

function onSuiteMoreSelect(key: string | number) {
  const action = String(key)
  if (action === 'rename') {
    renameForm.value = detail.value?.displayName ?? ''
    showRename.value = true
    return
  }
  if (action === 'delete') showDeleteConfirm.value = true
}

watch(
  () => [props.tenantId, props.kbId, props.selectedSuiteKey] as const,
  () => { void loadSuites() },
  { immediate: true },
)

defineExpose({ loadSuites, suites })
</script>

<template>
  <div class="suite-tab">
    <NSpin :show="loading" class="suite-spin">
      <div class="suite-layout">
        <aside class="list-panel">
          <div class="panel-head">
            <div class="panel-head-left">
              <span class="panel-title">评测集</span>
              <NTag :bordered="false" size="tiny" round>{{ filteredSuites.length }}</NTag>
            </div>
            <NButton size="small" round type="primary" class="action-btn create-head-btn" @click="openCreate">新建</NButton>
          </div>
          <div class="list-search">
            <NInput v-model:value="search" size="small" round clearable placeholder="搜索评测集…" class="search-input" />
          </div>
          <div class="list-body">
            <ul class="suite-list">
              <li
                v-for="item in filteredSuites"
                :key="item.suiteKey"
                :class="['suite-card', { active: item.suiteKey === editingKey }]"
                @click="selectSuite(item.suiteKey)"
              >
                <span class="suite-title">{{ item.displayName }}</span>
                <NText depth="3" class="suite-meta">
                  {{ item.itemCount }} 条 · {{ item.kind === 'python' ? 'Python' : '标准' }}
                </NText>
              </li>
              <NEmpty v-if="!loading && filteredSuites.length === 0" description="暂无评测集" size="small" />
            </ul>
          </div>
        </aside>
        <main v-if="detail" class="detail-panel">
          <div class="detail-panel-inner">
            <header class="detail-toolbar">
              <div class="detail-title-block">
                <h3>{{ detail.displayName }}</h3>
                <NText depth="3" class="detail-meta">
                  {{ detail.itemCount }} 条 · {{ detail.kind === 'python' ? 'Python 脚本' : '标准化配置' }}
                </NText>
              </div>
              <div v-if="isEditable" class="detail-actions">
                <NDropdown trigger="click" size="small" :options="suiteMoreOptions" @select="onSuiteMoreSelect">
                  <NButton size="small" quaternary class="more-menu-btn" title="更多操作" aria-label="更多操作">
                    <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
                  </NButton>
                </NDropdown>
              </div>
            </header>
            <div v-if="isStandard" class="detail-subbar">
              <div class="view-switch">
                <button type="button" class="view-btn" :class="{ active: detailView === 'items' }" @click="detailView = 'items'">条目列表</button>
                <button type="button" class="view-btn" :class="{ active: detailView === 'config' }" @click="detailView = 'config'">评测配置</button>
              </div>
              <NButton
                v-if="isEditable && detailView === 'items'"
                size="small"
                round
                type="primary"
                class="action-btn subbar-add-btn"
                @click="openAddItem"
              >
                添加条目
              </NButton>
              <NButton
                v-if="isEditable && detailView === 'config'"
                size="small"
                round
                type="primary"
                class="action-btn subbar-add-btn"
                :loading="saving"
                @click="handleSaveConfig"
              >
                保存配置
              </NButton>
            </div>
            <div class="detail-content">
              <div v-if="detailView === 'items' && isStandard" class="items-pane">
                <div v-if="items.length" class="items-table">
                  <div class="items-head"><span>问题</span><span>期望文档</span><span>分类</span><span /></div>
                  <div v-for="row in items" :key="row.itemKey" class="items-row">
                    <span>{{ row.queryText }}</span>
                    <span class="muted">{{ formatRelevantDocs(row.relevantDocIds) }}</span>
                    <span class="muted">{{ formatEvalCategory(row.category) }}</span>
                    <NButton
                      v-if="!detail.builtin"
                      size="tiny"
                      quaternary
                      circle
                      type="error"
                      title="删除"
                      @click="requestDeleteItem(row)"
                    >
                      <template #icon>
                        <NIcon :component="TrashOutline" />
                      </template>
                    </NButton>
                  </div>
                </div>
                <div v-else class="items-empty">
                  <NEmpty size="small" description="暂无条目，添加问题与期望文档开始构建评测集" />
                </div>
              </div>
              <div v-else-if="detailView === 'config' && isStandard" class="config-pane">
                <section class="config-section">
                  <h4 class="config-section-title">检索参数</h4>
                  <NForm label-placement="left" label-width="132" size="small" class="config-form">
                    <NFormItem label="TopK">
                      <NSelect
                        class="config-field-select"
                        :value="configDraft.topK"
                        :options="EVAL_TOP_K_OPTIONS"
                        multiple
                        :disabled="!isEditable"
                        placeholder="选择 Recall 计算的 K 值"
                        @update:value="updateTopK"
                      />
                    </NFormItem>
                    <NFormItem label="minScore">
                      <NInputNumber
                        class="config-field-control"
                        :value="configDraft.minScore"
                        :min="0"
                        :max="1"
                        :step="0.01"
                        :disabled="!isEditable"
                        @update:value="updateMinScore"
                      />
                    </NFormItem>
                  </NForm>
                </section>
                <section class="config-section">
                  <h4 class="config-section-title">通过门禁</h4>
                  <NForm label-placement="left" label-width="132" size="small" class="config-form">
                    <NFormItem label="Recall@3 ≥">
                      <NInputNumber
                        class="config-field-control"
                        :value="gateDraft.recallAt3Min"
                        :min="0"
                        :max="1"
                        :step="0.01"
                        :disabled="!isEditable"
                        @update:value="(v) => updateGateField('recallAt3Min', v)"
                      />
                    </NFormItem>
                    <NFormItem label="Recall@5 ≥">
                      <NInputNumber
                        class="config-field-control"
                        :value="gateDraft.recallAt5Min"
                        :min="0"
                        :max="1"
                        :step="0.01"
                        :disabled="!isEditable"
                        @update:value="(v) => updateGateField('recallAt5Min', v)"
                      />
                    </NFormItem>
                    <NFormItem label="MRR ≥">
                      <NInputNumber
                        class="config-field-control"
                        :value="gateDraft.mrrMin"
                        :min="0"
                        :max="1"
                        :step="0.01"
                        :disabled="!isEditable"
                        @update:value="(v) => updateGateField('mrrMin', v)"
                      />
                    </NFormItem>
                    <NFormItem label="正例 EmptyRate ≤">
                      <NInputNumber
                        class="config-field-control"
                        :value="gateDraft.emptyRatePositiveMax"
                        :min="0"
                        :max="1"
                        :step="0.01"
                        :disabled="!isEditable"
                        @update:value="(v) => updateGateField('emptyRatePositiveMax', v)"
                      />
                    </NFormItem>
                    <NFormItem label="负例 EmptyRate ≥">
                      <NInputNumber
                        class="config-field-control"
                        :value="gateDraft.emptyRateNegativeMin"
                        :min="0"
                        :max="1"
                        :step="0.01"
                        :disabled="!isEditable"
                        @update:value="(v) => updateGateField('emptyRateNegativeMin', v)"
                      />
                    </NFormItem>
                    <NFormItem label="P95 延迟 (ms) ≤">
                      <NInputNumber
                        class="config-field-control"
                        :value="gateDraft.latencyP95MsMax"
                        :min="0"
                        :step="10"
                        :disabled="!isEditable"
                        @update:value="(v) => updateGateField('latencyP95MsMax', v)"
                      />
                    </NFormItem>
                  </NForm>
                </section>
                <NText v-if="detail.builtin" depth="3" class="readonly-hint">内置评测集配置只读</NText>
              </div>
              <div v-else-if="detail.kind === 'python'" class="source-pane">
                <div class="source-preview">
                  <div class="preview-bar">
                    <NIcon :component="DocumentTextOutline" :size="14" />
                    <span class="preview-path">suite.py</span>
                  </div>
                  <div class="preview-scroll">
                    <div v-if="scriptPreviewHtml" class="skill-file-plain">
                      <pre class="skill-file-plain-pre">
                        <code class="hljs language-python" v-html="scriptPreviewHtml" />
                      </pre>
                    </div>
                    <div v-else class="preview-empty"><NEmpty size="small" description="无脚本内容" /></div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </main>
        <div v-else class="detail-empty"><NEmpty description="请选择评测集" /></div>
      </div>
    </NSpin>
    <NModal v-model:show="showCreate" preset="dialog" title="新建评测集" class="sunshine-dialog">
      <NForm label-placement="left" label-width="72">
        <NFormItem label="名称" required><NInput v-model:value="createForm.displayName" placeholder="例如：财务制度专项" /></NFormItem>
        <NFormItem label="Key" required><NInput v-model:value="createForm.suiteKey" placeholder="例如：finance_policy" /></NFormItem>
        <NFormItem label="类型">
          <NSelect
            v-model:value="createForm.kind"
            class="suite-field-select"
            :options="[
              { label: '标准评测集', value: 'standard' },
              { label: 'Python 脚本', value: 'python' },
            ]"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showCreate = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="saving" @click="handleCreate">创建</NButton>
      </template>
    </NModal>
    <NModal v-model:show="showDeleteConfirm" preset="dialog" title="删除评测集" class="sunshine-dialog">
      <p>确定删除「{{ detail?.displayName }}」？此操作不可恢复。</p>
      <template #action>
        <NButton @click="showDeleteConfirm = false">取消</NButton>
        <NButton type="error" :loading="deleting" @click="handleDeleteSuite">删除</NButton>
      </template>
    </NModal>
    <NModal v-model:show="showDeleteItemConfirm" preset="dialog" title="删除条目" class="sunshine-dialog">
      <p>确定删除条目「{{ deleteItemTarget?.queryText }}」？此操作不可恢复。</p>
      <template #action>
        <NButton @click="showDeleteItemConfirm = false">取消</NButton>
        <NButton type="error" :loading="deletingItem" @click="handleConfirmDeleteItem">删除</NButton>
      </template>
    </NModal>
    <NModal v-model:show="showRename" preset="dialog" title="重命名评测集" class="sunshine-dialog">
      <NForm label-placement="left" label-width="48">
        <NFormItem label="名称" required><NInput v-model:value="renameForm" placeholder="评测集显示名称" /></NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showRename = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="saving" @click="handleRename">保存</NButton>
      </template>
    </NModal>
    <NModal v-model:show="showAddItem" preset="dialog" title="添加评测条目" class="sunshine-dialog">
      <NForm label-placement="left" label-width="96">
        <NFormItem label="问题" required><NInput v-model:value="addForm.query" placeholder="用户会问的检索问题" /></NFormItem>
        <NFormItem label="期望文档">
          <NSelect
            class="suite-field-select"
            v-model:value="addForm.docIds"
            :options="docSelectOptions"
            :loading="loadingDocs"
            :disabled="!props.kbId || documents.length === 0"
            multiple
            filterable
            clearable
            placeholder="选择期望命中的文档"
          />
        </NFormItem>
        <NFormItem label="分类">
          <NSelect
            v-model:value="addForm.category"
            class="suite-field-select"
            :options="[...EVAL_CATEGORY_OPTIONS]"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showAddItem = false">取消</NButton>
        <NButton type="primary" class="action-btn" @click="handleAddItem">添加</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.suite-tab { height: 100%; min-height: 0; display: flex; flex-direction: column; }
.suite-spin { flex: 1; min-height: 0; }
.suite-spin :deep(.n-spin-content) { height: 100%; min-height: 0; }
.suite-layout { display: grid; grid-template-columns: minmax(280px, 320px) minmax(0, 1fr); gap: 16px; height: 100%; min-height: 0; }
.list-panel, .detail-panel, .detail-empty { border-radius: var(--radius-lg); border: 1px solid var(--sun-border); background: var(--sun-black); min-height: 0; overflow: hidden; }
.list-panel { display: flex; flex-direction: column; }
.panel-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-shrink: 0; padding: 14px 16px 0; }
.panel-head-left { display: flex; align-items: center; gap: 8px; min-width: 0; }
.panel-title { font-size: 14px; font-weight: 600; color: var(--sun-text); }
.list-search { flex-shrink: 0; padding: 10px 12px; }
.search-input { --n-color: var(--sun-black) !important; --n-color-focus: var(--sun-black) !important; --n-text-color: var(--sun-text) !important; --n-placeholder-color: var(--sun-text-muted) !important; --n-border: 1px solid var(--sun-border) !important; --n-border-focus: 1px solid var(--sun-border-light) !important; --n-border-hover: 1px solid var(--sun-border-light) !important; --n-box-shadow-focus: none !important; }
.create-head-btn { flex-shrink: 0; }
.list-body { flex: 1; min-height: 0; overflow-y: auto; padding: 0 10px 12px; }
.action-btn { --n-color: var(--sun-accent) !important; --n-color-hover: var(--sun-accent-hover) !important; --n-color-pressed: var(--sun-accent-hover) !important; --n-color-focus: var(--sun-accent-hover) !important; --n-text-color: var(--btn-primary-text) !important; --n-text-color-hover: var(--btn-primary-text) !important; --n-text-color-pressed: var(--btn-primary-text) !important; --n-text-color-focus: var(--btn-primary-text) !important; --n-border: none !important; }
.suite-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.suite-card { padding: 12px; border: 1px solid var(--sun-border); border-radius: var(--radius-md); cursor: pointer; display: flex; flex-direction: column; gap: 4px; background: var(--sun-black); }
.suite-card:hover { border-color: var(--sun-border-light); }
.suite-card.active { border-color: var(--sun-border-light); outline: 1px solid color-mix(in srgb, var(--sun-text-muted) 45%, transparent); outline-offset: -2px; }
.suite-title { font-size: 14px; font-weight: 600; line-height: 1.3; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.suite-meta { font-size: 11px; }
.detail-panel { display: flex; flex-direction: column; padding: 16px; min-height: 0; }
.detail-panel-inner { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: 12px; }
.detail-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-shrink: 0; padding-bottom: 12px; border-bottom: 1px solid var(--sun-border); }
.detail-title-block { min-width: 0; flex: 1; }
.detail-title-block h3 { margin: 0; font-size: 15px; font-weight: 600; line-height: 1.35; }
.detail-meta { display: block; margin-top: 4px; font-size: 12px; }
.detail-actions { display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex-shrink: 0; flex-wrap: wrap; }
.detail-subbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-shrink: 0; }
.subbar-add-btn { flex-shrink: 0; }
.more-menu-btn { --n-width: 32px; --n-height: 32px; padding: 0; }
.view-switch { display: inline-flex; border: 1px solid var(--sun-border); border-radius: var(--radius-md); overflow: hidden; }
.view-btn { border: none; background: transparent; color: var(--sun-text-secondary); font-size: 12px; padding: 5px 12px; cursor: pointer; }
.view-btn + .view-btn { border-left: 1px solid var(--sun-border); }
.view-btn.active { color: var(--sun-text); font-weight: 600; }
.detail-content { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; }
.items-pane, .config-pane { display: flex; flex-direction: column; gap: 10px; min-height: 0; }
.config-section { display: flex; flex-direction: column; gap: 8px; }
.config-section + .config-section { margin-top: 4px; padding-top: 16px; border-top: 1px solid var(--sun-border); }
.config-section-title { margin: 0; font-size: 13px; font-weight: 600; color: var(--sun-text-secondary); }
.config-form { max-width: 420px; }
.config-field-control { width: min(168px, 100%); }
.config-field-select { width: min(240px, 100%); }
.config-form :deep(.n-form-item-blank) { flex: 0 1 auto; min-width: 0; }
.config-form :deep(.n-base-selection) {
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
}
.config-form :deep(.n-base-selection-tags .n-tag) {
  --n-color: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
}
.config-form :deep(.n-input) {
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
.items-empty { flex: 1; min-height: 200px; display: flex; align-items: center; justify-content: center; }
.items-table { display: flex; flex-direction: column; gap: 4px; }
.items-head, .items-row { display: grid; grid-template-columns: 1.4fr 1fr 0.8fr 40px; gap: 8px; font-size: 13px; align-items: center; }
.items-head { position: sticky; top: 0; z-index: 1; background: var(--sun-black); font-size: 12px; color: var(--sun-text-muted); padding: 6px 0; border-bottom: 1px solid var(--sun-border); }
.items-row { padding: 8px 0; border-bottom: 1px solid var(--sun-border); }
.muted { color: var(--sun-text-muted); font-size: 12px; }
.readonly-hint { font-size: 12px; margin-top: 8px; }
.source-pane { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.source-preview { flex: 1; min-height: 0; display: flex; flex-direction: column; border: 1px solid var(--sun-border); border-radius: var(--radius-md); overflow: hidden; background: var(--sun-black); }
.preview-bar { display: flex; align-items: center; gap: 6px; padding: 8px 12px; border-bottom: 1px solid var(--sun-border); flex-shrink: 0; background: transparent; }
.preview-path { font-size: 12px; font-family: 'JetBrains Mono', monospace; color: var(--sun-text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-scroll { flex: 1; min-height: 0; overflow: auto; }
.preview-empty { display: flex; align-items: center; justify-content: center; min-height: 160px; }
.detail-empty { display: flex; align-items: center; justify-content: center; }
@media (max-width: 800px) { .suite-layout { grid-template-columns: 1fr; grid-template-rows: auto 1fr; } .list-panel { max-height: 240px; } }
:deep(.more-menu-delete) { color: var(--sun-danger, #d03050); }
</style>

<style>
/* 弹层挂 body，须全局选择器；与 config-form 同款黑底 */
.sunshine-dialog .n-base-selection,
.suite-field-select.n-select .n-base-selection {
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
}
.sunshine-dialog .n-base-selection-tags .n-tag {
  --n-color: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
}
.sunshine-dialog .n-input {
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
</style>
