<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NSpace, NText } from 'naive-ui'
import type { ConfigSuggestionItem, EvalReportView, EvalSuggestResult, TextSuggestionItem } from '../../api/ragAdmin'
import MetricBadge from './MetricBadge.vue'
import { EVAL_GATE_LABEL } from '../../utils/evalConstants'
import {
  collectSuggestionsForApply,
  configPathDisplayLabel,
  isConfigPromptPath,
  normalizeSuggestReason,
} from '../../utils/kbConfigPathLabel'

const props = defineProps<{
  report: EvalReportView
  jobId?: number | null
  suiteDisplayName?: string
  configVersionId?: number | null
  suggestResult?: EvalSuggestResult | null
  suggesting?: boolean
  suggestError?: string
  applying?: boolean
  /** 是否为该配置版本最新一条评测失败记录（控制生成/应用建议按钮） */
  allowSuggestActions?: boolean
}>()

const emit = defineEmits<{
  suggest: [regenerate?: boolean]
  applySuggestions: []
}>()

const resultView = ref<'overview' | 'failed' | 'suggest'>('overview')
const regenerating = ref(false)

const failedSamples = computed(() => props.report.failedSamples ?? [])

const showSuggestTab = computed(() => props.report.passedGate === false)

const gateLabel = computed(() =>
  props.report.passedGate ? EVAL_GATE_LABEL.pass : EVAL_GATE_LABEL.fail,
)

const configSuggestions = computed(() => props.suggestResult?.suggestions ?? [])

const applicableSuggestions = computed(() => collectSuggestionsForApply(props.suggestResult))

const textSuggestions = computed(() => props.suggestResult?.textSuggestions ?? [])

const showSuggestActions = computed(() => props.allowSuggestActions === true)

const applyTargetHint = computed(() => {
  if (props.configVersionId != null) {
    return '将应用建议并将该配置版本重置为草稿，可在「参数配置」Tab 编辑后重新提交评测'
  }
  return ''
})

const suggestActionsBlockedHint = computed(() => {
  if (showSuggestActions.value || props.report.passedGate !== false) return ''
  return '仅该配置版本最新一条评测失败记录可重新生成或应用参数建议'
})

watch(
  () => [props.report.passedGate, props.report.reportId] as const,
  ([passed]) => {
    if (passed === false) {
      resultView.value = 'suggest'
    }
  },
  { immediate: true },
)

function handleSuggestClick() {
  regenerating.value = !!props.suggestResult
  emit('suggest', regenerating.value)
}

watch(
  () => props.suggesting,
  (active) => {
    if (!active) regenerating.value = false
  },
)

function recallAt(k: string): string {
  const recall = props.report.summary?.recall_at_k as Record<string, number> | undefined
  const v = recall?.[k]
  return v != null ? v.toFixed(4) : '—'
}

function sampleQuery(row: Record<string, unknown>): string {
  return String(row.query ?? row.queryText ?? '—')
}

function sampleExpected(row: Record<string, unknown>): string {
  const exp = row.expected ?? row.relevant ?? row.relevant_docs
  if (Array.isArray(exp)) return exp.join('、')
  return String(exp ?? '—')
}

const gateCheck = computed(() => {
  const raw = props.report.summary?.gate_check
  if (!raw || typeof raw !== 'object') return null
  return raw as { passed?: boolean; failures?: string[] }
})

const gateFailures = computed(() => gateCheck.value?.failures ?? [])

const configGates = computed(() => {
  const raw = props.report.summary?.gates
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  return raw as Record<string, number>
})

const gateThresholdLines = computed(() => {
  const gates = configGates.value
  if (!gates) return []
  const lines: string[] = []
  if (gates.recallAt3Min != null) lines.push(`Recall@3 ≥ ${gates.recallAt3Min}`)
  if (gates.recallAt5Min != null) lines.push(`Recall@5 ≥ ${gates.recallAt5Min}`)
  if (gates.mrrMin != null) lines.push(`MRR ≥ ${gates.mrrMin}`)
  if (gates.emptyRatePositiveMax != null) lines.push(`正例 EmptyRate ≤ ${gates.emptyRatePositiveMax}`)
  if (gates.emptyRateNegativeMin != null) lines.push(`负例 EmptyRate ≥ ${gates.emptyRateNegativeMin}`)
  if (gates.latencyP95MsMax != null) lines.push(`P95 延迟 ≤ ${gates.latencyP95MsMax} ms`)
  return lines
})

function formatConfigValue(value: unknown): string {
  if (value === undefined || value === null) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function textKindLabel(kind: string): string {
  if (kind === 'prompt') return 'Prompt'
  if (kind === 'eval_query') return '评测问句'
  if (kind === 'document') return '文档'
  return kind || '文本'
}

function sampleTypeLabel(row: Record<string, unknown>): string {
  const t = String(row.sampleType ?? '')
  if (t === 'negative_false_positive') return '负例误召回'
  if (t === 'positive_miss') return '正例未命中'
  return ''
}

function sampleActual(row: Record<string, unknown>): string {
  const act = row.actual ?? row.retrieved ?? row.top_hits ?? row.top3
  if (Array.isArray(act)) {
    return act
      .map((item) => (typeof item === 'object' && item != null && 'docName' in item
        ? String((item as { docName?: string }).docName ?? item)
        : String(item)))
      .slice(0, 5)
      .join('、')
  }
  return String(act ?? '—')
}
</script>

<template>
  <div class="result-view">
    <div class="result-toolbar">
      <div class="result-summary">
        <MetricBadge :value="`Recall@5 ${report.recallAt5?.toFixed(4) ?? '—'}`" />
        <MetricBadge :value="`MRR ${report.mrr?.toFixed(4) ?? '—'}`" />
        <MetricBadge :value="`门禁 ${gateLabel}`" :empty="!report.passedGate" />
      </div>
      <div class="view-switch">
        <button type="button" class="view-btn" :class="{ active: resultView === 'overview' }" @click="resultView = 'overview'">
          概览
        </button>
        <button type="button" class="view-btn" :class="{ active: resultView === 'failed' }" @click="resultView = 'failed'">
          失败样本
        </button>
        <button
          v-if="showSuggestTab"
          type="button"
          class="view-btn"
          :class="{ active: resultView === 'suggest' }"
          @click="resultView = 'suggest'"
        >
          优化建议
        </button>
      </div>
    </div>
    <div class="result-body">
      <template v-if="resultView === 'overview'">
        <NText depth="3" class="meta-line">
          任务 #{{ jobId ?? report.jobId }}
          <span v-if="suiteDisplayName"> · {{ suiteDisplayName }}</span>
        </NText>
        <div class="metric-grid">
          <span>Recall@3 {{ recallAt('3') }}</span>
          <span>Recall@5 {{ recallAt('5') }}</span>
          <span>Recall@10 {{ recallAt('10') }}</span>
        </div>
        <div v-if="gateThresholdLines.length" class="gate-thresholds">
          <NText depth="3" class="gate-label">本评测集门禁标准</NText>
          <span v-for="line in gateThresholdLines" :key="line" class="gate-threshold">{{ line }}</span>
        </div>
        <div v-if="!report.passedGate" class="gate-failures">
          <NText type="warning" class="gate-fail-title">未通过原因</NText>
          <ul v-if="gateFailures.length" class="gate-fail-list">
            <li v-for="(item, idx) in gateFailures" :key="idx">{{ item }}</li>
          </ul>
          <NText v-else-if="report.baselineRecallAt5 != null && report.recallAt5 != null" depth="3" class="gate-fail-fallback">
            Recall@5 {{ report.recallAt5.toFixed(4) }} 低于基线 {{ report.baselineRecallAt5.toFixed(4) }}
          </NText>
        </div>
      </template>
      <template v-else-if="resultView === 'failed'">
        <div v-if="failedSamples.length > 0" class="failed-table">
          <div class="failed-head">
            <span>问题</span>
            <span>期望文档</span>
            <span>实际命中</span>
          </div>
          <div v-for="(row, idx) in failedSamples" :key="idx" class="failed-row">
            <span>
              <span v-if="sampleTypeLabel(row)" class="sample-type">{{ sampleTypeLabel(row) }}</span>
              {{ sampleQuery(row) }}
            </span>
            <span>{{ sampleExpected(row) }}</span>
            <span>{{ sampleActual(row) }}</span>
          </div>
        </div>
        <NEmpty v-else size="small" description="无失败样本" />
      </template>
      <template v-else>
        <NSpace v-if="showSuggestActions" align="center" wrap>
          <NButton
            size="small"
            round
            secondary
            :loading="suggesting"
            :disabled="suggesting || applying"
            @click="handleSuggestClick"
          >
            {{ suggestResult ? '重新生成' : '生成优化建议' }}
          </NButton>
          <NButton
            size="small"
            round
            type="primary"
            :loading="applying"
            :disabled="!applicableSuggestions.length"
            @click="emit('applySuggestions')"
          >
            一键应用参数建议
          </NButton>
        </NSpace>
        <NText v-if="showSuggestActions && applicableSuggestions.length && applyTargetHint" depth="3" class="apply-hint">{{ applyTargetHint }}</NText>
        <NText v-if="suggestError" type="error" class="apply-hint">{{ suggestError }}</NText>
        <NText v-if="suggestActionsBlockedHint" depth="3" class="apply-hint">{{ suggestActionsBlockedHint }}</NText>
        <div v-if="suggesting" class="suggest-loading">
          <NText depth="3">{{ regenerating ? '正在重新生成优化建议…' : '正在分析失败样本并生成优化建议…' }}</NText>
        </div>
        <section v-if="!suggesting && suggestResult?.diagnosis" class="suggest-section">
          <h4 class="section-title">诊断</h4>
          <p class="diagnosis">{{ suggestResult.diagnosis }}</p>
        </section>
        <section v-if="!suggesting && textSuggestions.length" class="suggest-section">
          <h4 class="section-title">文本优化</h4>
          <p class="section-desc">以下建议需人工审核后修改 Prompt 或评测集条目</p>
          <ul class="text-suggest-list">
            <li v-for="(item, idx) in textSuggestions" :key="idx">
              <div class="text-suggest-head">
                <strong>{{ (item as TextSuggestionItem).target || '—' }}</strong>
                <span class="kind-tag">{{ textKindLabel((item as TextSuggestionItem).kind) }}</span>
              </div>
              <div v-if="(item as TextSuggestionItem).current" class="text-block">
                <span class="text-label">当前</span>
                <pre class="text-pre">{{ (item as TextSuggestionItem).current }}</pre>
              </div>
              <div class="text-block">
                <span class="text-label">建议</span>
                <pre class="text-pre">{{ (item as TextSuggestionItem).proposed }}</pre>
              </div>
              <span v-if="(item as TextSuggestionItem).reason" class="text-reason">
                {{ (item as TextSuggestionItem).reason }}
              </span>
            </li>
          </ul>
        </section>
        <section v-if="!suggesting && configSuggestions.length" class="suggest-section">
          <h4 class="section-title">参数配置优化</h4>
          <ul class="config-suggest-list">
            <li v-for="(item, idx) in configSuggestions" :key="idx">
              <div class="config-suggest-head">
                <strong>{{ configPathDisplayLabel((item as ConfigSuggestionItem).path) }}</strong>
              </div>
              <template v-if="isConfigPromptPath((item as ConfigSuggestionItem).path)">
                <div v-if="(item as ConfigSuggestionItem).current != null" class="text-block">
                  <span class="text-label">当前</span>
                  <pre class="text-pre">{{ formatConfigValue((item as ConfigSuggestionItem).current) }}</pre>
                </div>
                <div class="text-block">
                  <span class="text-label">建议</span>
                  <pre class="text-pre">{{ formatConfigValue((item as ConfigSuggestionItem).proposed) }}</pre>
                </div>
              </template>
              <div v-else class="config-suggest-change">
                <span class="text-label">建议调整</span>
                <span class="suggest-value">
                  {{ formatConfigValue((item as ConfigSuggestionItem).current) }}
                  →
                  {{ formatConfigValue((item as ConfigSuggestionItem).proposed) }}
                </span>
              </div>
              <div
                v-if="normalizeSuggestReason((item as ConfigSuggestionItem).reason)"
                class="config-suggest-reason"
              >
                <span class="reason-label">修改理由</span>
                <p class="reason-text">{{ normalizeSuggestReason((item as ConfigSuggestionItem).reason) }}</p>
              </div>
            </li>
          </ul>
        </section>
        <NEmpty
          v-if="!suggesting && !suggestResult?.diagnosis && !configSuggestions.length && !textSuggestions.length"
          size="small"
          description="点击生成优化建议，或由系统自动分析"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.result-view {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  overflow: hidden;
}
.result-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
  flex-wrap: wrap;
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
.result-body {
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.meta-line {
  font-size: 12px;
}
.metric-grid {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: var(--sun-text-secondary);
}
.gate-thresholds {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.gate-label {
  white-space: nowrap;
}
.gate-threshold {
  padding: 2px 8px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  color: var(--sun-text-secondary);
}
.gate-failures {
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.gate-fail-title {
  font-size: 13px;
  font-weight: 600;
}
.gate-fail-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--sun-text-secondary);
}
.gate-fail-fallback {
  font-size: 13px;
}
.failed-table {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.failed-head,
.failed-row {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr;
  gap: 8px;
  font-size: 13px;
}
.failed-head {
  color: var(--sun-text-muted);
  font-size: 12px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--sun-border);
}
.failed-row {
  padding: 6px 0;
  color: var(--sun-text-secondary);
}
.sample-type {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  font-size: 11px;
  line-height: 18px;
  border: 1px solid var(--sun-border);
  border-radius: 4px;
  color: var(--sun-text-muted);
}
.apply-hint {
  font-size: 12px;
}
.suggest-loading {
  padding: 8px 0;
}
.suggest-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}
.section-desc {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}
.diagnosis {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--sun-text-secondary);
}
.suggest-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--sun-text-secondary);
}
.suggest-list li {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 8px;
}
.suggest-value {
  font-family: var(--sun-font-mono, ui-monospace, monospace);
  font-size: 12px;
  color: var(--sun-text);
}
.text-suggest-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.text-suggest-list li {
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.text-suggest-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}
.kind-tag {
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  color: var(--sun-text-muted);
}
.text-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.text-label {
  font-size: 11px;
  color: var(--sun-text-muted);
}
.text-pre {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text-secondary);
  background: transparent;
  max-height: 200px;
  overflow-y: auto;
}
.text-reason {
  font-size: 12px;
  color: var(--sun-text-muted);
}
.config-suggest-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.config-suggest-list li {
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.config-suggest-head {
  font-size: 13px;
  color: var(--sun-text);
}
.config-suggest-change {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.config-suggest-reason {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-top: 2px;
  border-top: 1px solid var(--sun-border);
}
.reason-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text-muted);
}
.reason-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--sun-text-secondary);
}
</style>
