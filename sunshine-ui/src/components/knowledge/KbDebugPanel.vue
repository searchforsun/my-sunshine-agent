<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSwitch,
  NText,
  useMessage,
} from 'naive-ui'
import {
  debugSearch,
  ensureKbCustomEvalSuite,
  listDocuments,
  listEvalSuites,
  mutateEvalSuiteQuery,
  type DebugSearchResponse,
  type EvalSuiteSummary,
  type KbDocument,
} from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import DebugFinalPanel from './DebugFinalPanel.vue'
import RetrievalWaterfall from './RetrievalWaterfall.vue'
import MetricBadge from './MetricBadge.vue'
import {
  useKbWorkbenchContext,
  appliedConfigToApi,
} from '../../composables/useKbWorkbenchContext'
import { DEFAULT_EVAL_CATEGORY, EVAL_CATEGORY_OPTIONS, type EvalCategory, kbCustomSuiteKey } from '../../utils/evalConstants'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
}>()

const wb = useKbWorkbenchContext()
const message = useMessage()

const query = ref('')
const strategy = ref<string>('')
const includeRewrite = ref(true)
const searching = ref(false)
const error = ref('')
const result = ref<DebugSearchResponse | null>(null)
const resultView = ref<'split' | 'final' | 'waterfall'>('split')
const showAddToSuite = ref(false)
const addTargetSuite = ref('')
const addExpectedDocIds = ref<string[]>([])
const addCategory = ref<EvalCategory>(DEFAULT_EVAL_CATEGORY)
const evalSuites = ref<EvalSuiteSummary[]>([])
const documents = ref<KbDocument[]>([])
const loadingDocs = ref(false)
const addingToSuite = ref(false)

const customSuiteKey = computed(() => (props.kbId ? kbCustomSuiteKey(props.kbId) : ''))

const suiteOptions = computed(() =>
  evalSuites.value
    .filter((s) => !s.builtin)
    .map((s) => ({ label: s.displayName, value: s.suiteKey })),
)

const docSelectOptions = computed(() =>
  documents.value.map((d) => ({ label: d.displayName, value: d.docId })),
)

function resolveDocIdByName(name: string): string | null {
  const trimmed = name.trim()
  if (!trimmed) return null
  const byId = documents.value.find((d) => d.docId === trimmed)
  if (byId) return byId.docId
  const byName = documents.value.find((d) => d.displayName === trimmed)
  return byName?.docId ?? null
}

const strategyOptions = [
  { label: '默认策略', value: '' },
  { label: 'vector', value: 'vector' },
  { label: 'hybrid', value: 'hybrid' },
  { label: 'hybrid+rerank', value: 'hybrid+rerank' },
]

const rewriteStageCount = computed(() =>
  (result.value?.stages ?? []).filter((s) => ['rag', 'hyde', 'empty-recall'].includes(s.name)).length,
)

async function loadEvalSuites() {
  if (!props.kbId) return
  try {
    await ensureKbCustomEvalSuite(props.tenantId, props.kbId)
    evalSuites.value = await listEvalSuites(props.tenantId)
    addTargetSuite.value = customSuiteKey.value || evalSuites.value.find((s) => !s.builtin)?.suiteKey || ''
  } catch {
    // ignore
  }
}

async function loadDocuments() {
  if (!props.kbId) {
    documents.value = []
    return
  }
  loadingDocs.value = true
  try {
    documents.value = await listDocuments(props.tenantId, props.kbId)
  } catch {
    documents.value = []
  } finally {
    loadingDocs.value = false
  }
}

async function openAddToSuite() {
  if (!query.value.trim()) return
  addCategory.value = DEFAULT_EVAL_CATEGORY
  await Promise.all([loadEvalSuites(), loadDocuments()])
  const topDocName = result.value?.final[0]?.docName ?? ''
  const preselect = resolveDocIdByName(topDocName)
  addExpectedDocIds.value = preselect ? [preselect] : []
  showAddToSuite.value = true
}

async function handleAddToSuite() {
  if (!props.kbId || !addTargetSuite.value || !query.value.trim()) return
  addingToSuite.value = true
  try {
    await mutateEvalSuiteQuery(props.tenantId, addTargetSuite.value, {
      action: 'add',
      query: query.value.trim(),
      relevantDocIds: addExpectedDocIds.value,
      category: addCategory.value,
    })
    const name = evalSuites.value.find((s) => s.suiteKey === addTargetSuite.value)?.displayName ?? '评测集'
    message.success(`已加入「${name}」`)
    showAddToSuite.value = false
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加入失败'))
  } finally {
    addingToSuite.value = false
  }
}

async function handleDebug() {
  if (!props.kbId || !query.value.trim()) return
  searching.value = true
  error.value = ''
  result.value = null
  try {
    result.value = await debugSearch(props.tenantId, props.kbId, {
      query: query.value.trim(),
      topK: 5,
      strategy: strategy.value || undefined,
      includeRewrite: includeRewrite.value,
      ...appliedConfigToApi(wb.appliedConfig.value),
    })
    resultView.value = 'split'
  } catch (e) {
    error.value = friendlyErrorMessage(e, '调试检索失败')
  } finally {
    searching.value = false
  }
}

function resetPanelState() {
  query.value = ''
  strategy.value = ''
  includeRewrite.value = true
  searching.value = false
  error.value = ''
  result.value = null
  resultView.value = 'split'
}

watch(
  () => wb.revision.value,
  () => {
    resetPanelState()
  },
)
</script>

<template>
  <div class="debug-panel">
    <div class="debug-form">
      <NInput
        v-model:value="query"
        placeholder="输入查询…"
        size="large"
        round
        class="kb-input"
        :disabled="!kbId"
        @keydown.enter="handleDebug"
      />
      <div class="debug-toolbar">
        <NSelect
          v-model:value="strategy"
          :options="strategyOptions"
          size="small"
          class="strategy-select"
          placeholder="策略"
          :menu-props="{ class: 'strategy-select-menu' }"
        />
        <label class="rewrite-toggle">
          <NSwitch v-model:value="includeRewrite" size="small" />
          <span>Query 改写</span>
        </label>
        <NButton
          v-if="result"
          size="small"
          round
          secondary
          :disabled="!kbId || !query.trim()"
          @click="openAddToSuite"
        >
          加入评测集
        </NButton>
        <NButton
          type="primary"
          class="action-btn"
          round
          :loading="searching"
          :disabled="!kbId || !query.trim()"
          @click="handleDebug"
        >
          调试检索
        </NButton>
      </div>
      <NText v-if="error" type="error" class="error-text">{{ error }}</NText>
    </div>

    <div v-if="result" class="debug-body">
      <div class="result-toolbar">
        <div class="result-summary">
          <MetricBadge :value="`最终 ${result.final.length} 条`" />
          <MetricBadge v-if="rewriteStageCount > 0" :value="`改写 ${rewriteStageCount}`" />
          <MetricBadge :value="`瀑布 ${result.stages.length} 阶段`" />
        </div>
        <div class="view-switch">
          <button
            type="button"
            class="view-btn"
            :class="{ active: resultView === 'split' }"
            @click="resultView = 'split'"
          >
            分栏
          </button>
          <button
            type="button"
            class="view-btn"
            :class="{ active: resultView === 'final' }"
            @click="resultView = 'final'"
          >
            仅结果
          </button>
          <button
            type="button"
            class="view-btn"
            :class="{ active: resultView === 'waterfall' }"
            @click="resultView = 'waterfall'"
          >
            仅瀑布
          </button>
        </div>
      </div>

      <div
        class="debug-split"
        :class="{
          'debug-split--final': resultView === 'final',
          'debug-split--waterfall': resultView === 'waterfall',
        }"
      >
        <section v-show="resultView !== 'waterfall'" class="result-pane">
          <header class="pane-head">
            <span class="pane-title">最终结果</span>
          </header>
          <div class="pane-scroll">
            <DebugFinalPanel v-if="result.final.length > 0" :hits="result.final" />
            <NEmpty v-else size="small" description="无命中" />
          </div>
        </section>

        <section v-show="resultView !== 'final'" class="result-pane result-pane--waterfall">
          <header class="pane-head">
            <span class="pane-title">检索瀑布</span>
          </header>
          <RetrievalWaterfall :stages="result.stages" />
        </section>
      </div>
    </div>
    <NModal v-model:show="showAddToSuite" preset="dialog" title="加入评测集" class="sunshine-dialog">
      <NForm label-placement="left" label-width="96">
        <NFormItem label="问题">
          <NInput :value="query" disabled />
        </NFormItem>
        <NFormItem label="目标评测集">
          <NSelect
            v-model:value="addTargetSuite"
            class="suite-field-select"
            :options="suiteOptions"
            placeholder="选择评测集"
          />
        </NFormItem>
        <NFormItem label="期望文档">
          <NSelect
            v-model:value="addExpectedDocIds"
            class="suite-field-select"
            :options="docSelectOptions"
            :loading="loadingDocs"
            :disabled="!kbId || documents.length === 0"
            multiple
            filterable
            clearable
            placeholder="选择期望命中的文档"
            :menu-props="{ class: 'strategy-select-menu' }"
          />
        </NFormItem>
        <NFormItem label="分类">
          <NSelect
            v-model:value="addCategory"
            class="suite-field-select"
            :options="[...EVAL_CATEGORY_OPTIONS]"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <div class="dialog-action-group">
          <NButton @click="showAddToSuite = false">取消</NButton>
          <NButton type="primary" class="action-btn" :loading="addingToSuite" @click="handleAddToSuite">加入</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.debug-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

.debug-form {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.kb-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.debug-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.strategy-select {
  width: 160px;
}

.strategy-select :deep(.n-base-selection) {
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

.rewrite-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--sun-text-secondary);
  cursor: pointer;
  user-select: none;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-color-disabled: var(--sun-border) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-border: none !important;
  --n-border-disabled: none !important;
  margin-left: auto;
}

.error-text {
  font-size: 13px;
}

.debug-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  background: var(--sun-black);
}

.result-toolbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
}

.result-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.view-switch {
  display: inline-flex;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  flex-shrink: 0;
}

.view-btn {
  border: none;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: 12px;
  padding: 5px 10px;
  cursor: pointer;
}

.view-btn + .view-btn {
  border-left: 1px solid var(--sun-border);
}

.view-btn.active {
  color: var(--sun-text);
  font-weight: 600;
}

.view-btn:hover {
  color: var(--sun-text);
}

.debug-split {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
}

.debug-split--final {
  grid-template-columns: minmax(0, 1fr);
}

.debug-split--waterfall {
  grid-template-columns: minmax(0, 1fr);
}

.result-pane {
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.result-pane--waterfall {
  border-left: 1px solid var(--sun-border);
}

.result-pane--waterfall :deep(.waterfall) {
  flex: 1;
  min-height: 0;
}

.debug-split--final .result-pane--waterfall,
.debug-split--waterfall .result-pane:not(.result-pane--waterfall) {
  border-left: none;
}

.pane-head {
  flex-shrink: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.pane-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.pane-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 12px;
}

@media (max-width: 960px) {
  .debug-split {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(180px, 0.45fr) minmax(220px, 0.55fr);
  }

  .result-pane--waterfall {
    border-left: none;
    border-top: 1px solid var(--sun-border);
  }
}
</style>

<style>
.strategy-select-menu.n-base-select-menu {
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
/* 弹层挂 body，须全局选择器；与评测集/文档弹窗同款黑底 */
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
.sunshine-dialog .dialog-action-group {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
}
.sunshine-dialog .dialog-action-group .action-btn {
  margin-left: 0;
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
