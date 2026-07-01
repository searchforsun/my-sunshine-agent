<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NEmpty,
  NInput,
  NSelect,
  NSwitch,
  NText,
} from 'naive-ui'
import { debugSearch, type DebugSearchResponse } from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { friendlyErrorMessage } from '../../api/apiError'
import DebugFinalPanel from './DebugFinalPanel.vue'
import RetrievalWaterfall from './RetrievalWaterfall.vue'
import MetricBadge from './MetricBadge.vue'

const props = defineProps<{
  tenantId: TenantId
  kbId: string | null
}>()

const query = ref('')
const strategy = ref<string>('')
const includeRewrite = ref(true)
const searching = ref(false)
const error = ref('')
const result = ref<DebugSearchResponse | null>(null)
const resultView = ref<'split' | 'final' | 'waterfall'>('split')

const strategyOptions = [
  { label: '默认策略', value: '' },
  { label: 'vector', value: 'vector' },
  { label: 'hybrid', value: 'hybrid' },
  { label: 'hybrid+rerank', value: 'hybrid+rerank' },
]

const rewriteStageCount = computed(() =>
  (result.value?.stages ?? []).filter((s) => ['rag', 'hyde', 'empty-recall'].includes(s.name)).length,
)

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
    })
    resultView.value = 'split'
  } catch (e) {
    error.value = friendlyErrorMessage(e, '调试检索失败')
  } finally {
    searching.value = false
  }
}

watch(
  () => props.kbId,
  () => {
    result.value = null
    error.value = ''
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
</style>
