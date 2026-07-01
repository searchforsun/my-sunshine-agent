<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DebugStage } from '../../api/ragAdmin'
import { NEmpty, NText } from 'naive-ui'
import MetricBadge from './MetricBadge.vue'

const props = defineProps<{
  stages: DebugStage[]
}>()

const activeStage = ref<string>('')
const expandedKeys = ref<Set<string>>(new Set())

const STAGE_LABELS: Record<string, string> = {
  rag: 'Query 改写',
  hyde: 'HyDE',
  'empty-recall': 'Empty Recall',
  vector: '向量',
  bm25: 'BM25',
  rrf: 'RRF',
  rerank: 'Rerank',
  filter: '过滤',
}

function stageLabel(name: string): string {
  return STAGE_LABELS[name] ?? name
}

function stageKey(stage: DebugStage, idx: number): string {
  return `${stage.name}-${idx}`
}

function candidateCount(stage: DebugStage): number {
  return (stage.candidates?.length ?? 0) + (stage.dropped?.length ?? 0)
}

function rowKey(prefix: string, idx: number): string {
  return `${activeStage.value}:${prefix}:${idx}`
}

const stageItems = computed(() =>
  props.stages.map((stage, idx) => ({
    stage,
    idx,
    key: stageKey(stage, idx),
    label: stageLabel(stage.name),
    count: candidateCount(stage),
  })),
)

const activeItem = computed(() =>
  stageItems.value.find((item) => item.key === activeStage.value) ?? null,
)

watch(
  () => props.stages,
  (stages) => {
    activeStage.value = stages.length > 0 ? stageKey(stages[0], 0) : ''
  },
  { immediate: true },
)

watch(activeStage, (key) => {
  expandedKeys.value = key ? new Set([`${key}:c:0`]) : new Set()
})

function selectStage(key: string) {
  activeStage.value = key
}

function isExpanded(key: string): boolean {
  return expandedKeys.value.has(key)
}

function toggleRow(key: string) {
  const next = new Set(expandedKeys.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expandedKeys.value = next
}

function preview(content: string, max = 96): string {
  const text = content.replace(/\s+/g, ' ').trim()
  if (text.length <= max) return text
  return `${text.slice(0, max)}…`
}
</script>

<template>
  <div class="waterfall">
    <NEmpty v-if="stages.length === 0" size="small" description="暂无阶段" />

    <template v-else>
      <div class="stage-track-wrap">
        <div class="stage-track" role="tablist" aria-label="检索阶段">
          <template v-for="(item, index) in stageItems" :key="item.key">
            <button
              type="button"
              role="tab"
              class="stage-step"
              :class="{ active: activeStage === item.key }"
              :aria-selected="activeStage === item.key"
              @click="selectStage(item.key)"
            >
              <span class="step-order">{{ index + 1 }}</span>
              <span class="step-main">
                <span class="step-label">{{ item.label }}</span>
                <span class="step-stats">
                  <MetricBadge :value="`${item.stage.latencyMs}ms`" />
                  <MetricBadge
                    :value="item.count > 0 ? `${item.count} 条` : '—'"
                    :empty="item.count === 0"
                  />
                </span>
              </span>
            </button>
            <span v-if="index < stageItems.length - 1" class="stage-connector" aria-hidden="true" />
          </template>
        </div>
      </div>

      <div v-if="activeItem" class="stage-detail-scroll">
        <div class="stage-detail">
          <div v-if="activeItem.stage.from" class="rewrite-line">
            <NText depth="3">from</NText>
            <span>{{ activeItem.stage.from }}</span>
          </div>
          <div v-if="activeItem.stage.to" class="rewrite-line">
            <NText depth="3">to</NText>
            <span>{{ activeItem.stage.to }}</span>
          </div>

          <div
            v-if="activeItem.stage.candidates && activeItem.stage.candidates.length > 0"
            class="candidate-list"
          >
            <article
              v-for="(c, cIdx) in activeItem.stage.candidates"
              :key="`c-${cIdx}`"
              class="candidate-row"
              :class="{ expanded: isExpanded(rowKey('c', cIdx)) }"
            >
              <button
                type="button"
                class="candidate-head"
                @click="toggleRow(rowKey('c', cIdx))"
              >
                <span class="cell-doc-name">{{ c.docName }}</span>
                <MetricBadge :value="c.score.toFixed(4)" />
                <NText depth="3" class="expand-hint">
                  {{ isExpanded(rowKey('c', cIdx)) ? '收起' : '展开' }}
                </NText>
              </button>
              <p v-if="!isExpanded(rowKey('c', cIdx))" class="candidate-preview">
                {{ preview(c.content) }}
              </p>
              <div v-else class="candidate-content">{{ c.content }}</div>
            </article>
          </div>

          <div v-if="activeItem.stage.dropped && activeItem.stage.dropped.length > 0" class="dropped-block">
            <div class="dropped-title">已过滤 · {{ activeItem.stage.dropped.length }}</div>
            <article
              v-for="(c, dIdx) in activeItem.stage.dropped"
              :key="`d-${dIdx}`"
              class="candidate-row dropped"
              :class="{ expanded: isExpanded(rowKey('d', dIdx)) }"
            >
              <button
                type="button"
                class="candidate-head"
                @click="toggleRow(rowKey('d', dIdx))"
              >
                <span class="cell-doc-name">{{ c.docName }}</span>
                <MetricBadge :value="c.score.toFixed(4)" />
                <NText depth="3" class="expand-hint">
                  {{ isExpanded(rowKey('d', dIdx)) ? '收起' : '展开' }}
                </NText>
              </button>
              <p v-if="!isExpanded(rowKey('d', dIdx))" class="candidate-preview">
                {{ preview(c.content) }}
              </p>
              <div v-else class="candidate-content">{{ c.content }}</div>
            </article>
          </div>

          <NText
            v-if="
              (!activeItem.stage.candidates || activeItem.stage.candidates.length === 0)
                && !activeItem.stage.from
            "
            depth="3"
          >
            无候选
          </NText>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.waterfall {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.stage-track-wrap {
  flex-shrink: 0;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.stage-track {
  display: flex;
  align-items: stretch;
  gap: 0;
  overflow-x: auto;
  padding-bottom: 2px;
}

.stage-step {
  flex: 1 0 88px;
  min-width: 88px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.stage-step.active {
  border-color: var(--sun-border-light);
  box-shadow: inset 0 0 0 1px var(--sun-border-light);
}

.stage-step:hover {
  border-color: var(--sun-border-light);
}

.step-order {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 999px;
  border: 1px solid var(--sun-border-light);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text-secondary);
  margin-top: 1px;
}

.stage-step.active .step-order {
  background: var(--sun-text);
  border-color: var(--sun-text);
  color: var(--sun-black);
}

.step-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.step-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text);
  white-space: nowrap;
}

.step-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.stage-connector {
  flex: 0 0 14px;
  align-self: center;
  position: relative;
  height: 1px;
  background: var(--sun-border);
}

.stage-connector::after {
  content: '';
  position: absolute;
  right: 0;
  top: 50%;
  width: 6px;
  height: 6px;
  border-top: 1px solid var(--sun-border);
  border-right: 1px solid var(--sun-border);
  transform: translateY(-50%) rotate(45deg);
}

.stage-detail-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
}

.stage-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rewrite-line {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 8px;
  font-size: 12px;
  color: var(--sun-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.candidate-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.candidate-row {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm);
  background: var(--sun-black);
  overflow: hidden;
}

.candidate-row.expanded {
  box-shadow: inset 0 0 0 1px var(--sun-border-light);
}

.candidate-row.dropped {
  opacity: 0.72;
}

.candidate-head {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.cell-doc-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expand-hint {
  font-size: 11px;
}

.candidate-preview {
  margin: 0;
  padding: 0 10px 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.candidate-content {
  margin: 0 10px 10px;
  padding-top: 10px;
  border-top: 1px solid var(--sun-border);
  font-size: 12px;
  line-height: 1.55;
  color: var(--sun-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.dropped-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.dropped-title {
  font-size: 11px;
  color: var(--sun-text-muted);
}
</style>
