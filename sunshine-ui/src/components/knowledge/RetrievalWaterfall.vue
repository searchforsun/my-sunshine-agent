<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DebugStage } from '../../api/ragAdmin'
import { NEmpty, NText } from 'naive-ui'
import MetricBadge from './MetricBadge.vue'

const props = defineProps<{
  stages: DebugStage[]
}>()

const activeStage = ref<string>('')
/** 与 L1 一致：当前阶段内同时仅展开一条 */
const expandedKey = ref<string | null>(null)

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
  if (!key) {
    expandedKey.value = null
    return
  }
  const item = stageItems.value.find((s) => s.key === key)
  if (item?.stage.candidates?.length) {
    expandedKey.value = `${key}:c:0`
  } else if (item?.stage.dropped?.length) {
    expandedKey.value = `${key}:d:0`
  } else {
    expandedKey.value = null
  }
})

function selectStage(key: string) {
  activeStage.value = key
}

function isExpanded(key: string): boolean {
  return expandedKey.value === key
}

function toggleRow(key: string) {
  expandedKey.value = expandedKey.value === key ? null : key
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
              class="hit-row"
              :class="{ expanded: isExpanded(rowKey('c', cIdx)) }"
              role="button"
              tabindex="0"
              @click="toggleRow(rowKey('c', cIdx))"
              @keydown.enter.prevent="toggleRow(rowKey('c', cIdx))"
              @keydown.space.prevent="toggleRow(rowKey('c', cIdx))"
            >
              <header class="hit-row-head">
                <MetricBadge :value="`#${cIdx + 1}`" />
                <span class="hit-doc">{{ c.docName }}</span>
                <MetricBadge :value="c.score.toFixed(4)" />
              </header>
              <div class="hit-row-scroll">
                <div class="hit-content">{{ c.content }}</div>
              </div>
            </article>
          </div>

          <div v-if="activeItem.stage.dropped && activeItem.stage.dropped.length > 0" class="dropped-block">
            <div class="dropped-title">已过滤 · {{ activeItem.stage.dropped.length }}</div>
            <article
              v-for="(c, dIdx) in activeItem.stage.dropped"
              :key="`d-${dIdx}`"
              class="hit-row dropped"
              :class="{ expanded: isExpanded(rowKey('d', dIdx)) }"
              role="button"
              tabindex="0"
              @click="toggleRow(rowKey('d', dIdx))"
              @keydown.enter.prevent="toggleRow(rowKey('d', dIdx))"
              @keydown.space.prevent="toggleRow(rowKey('d', dIdx))"
            >
              <header class="hit-row-head">
                <MetricBadge :value="`#${dIdx + 1}`" />
                <span class="hit-doc">{{ c.docName }}</span>
                <MetricBadge :value="c.score.toFixed(4)" />
              </header>
              <div class="hit-row-scroll">
                <div class="hit-content">{{ c.content }}</div>
              </div>
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

.candidate-list,
.dropped-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dropped-title {
  font-size: 11px;
  color: var(--sun-text-muted);
}

.hit-row {
  height: 220px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  background: var(--sun-black);
  cursor: pointer;
  transition: height 0.18s ease, border-color 0.15s ease;
}

.hit-row:hover {
  border-color: var(--sun-text-muted);
}

.hit-row.expanded {
  height: 480px;
  border-color: var(--sun-text);
}

.hit-row.dropped {
  opacity: 0.72;
}

.hit-row-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  min-width: 0;
}

.hit-doc {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hit-row-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.hit-content {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
}
</style>
