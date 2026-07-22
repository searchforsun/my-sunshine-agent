<script setup lang="ts">
import { ref, watch } from 'vue'
import MetricBadge from './MetricBadge.vue'

export interface DebugHit {
  docName: string
  content: string
  score: number
}

const props = defineProps<{
  hits: DebugHit[]
}>()

/** 与 L1 一致：同时仅展开一条 */
const expandedKey = ref<string | null>(null)

watch(
  () => props.hits,
  () => {
    expandedKey.value = props.hits.length > 0 ? '0' : null
  },
  { immediate: true },
)

function rowKey(idx: number): string {
  return String(idx)
}

function toggle(idx: number) {
  const key = rowKey(idx)
  expandedKey.value = expandedKey.value === key ? null : key
}

function isExpanded(idx: number): boolean {
  return expandedKey.value === rowKey(idx)
}
</script>

<template>
  <div class="final-panel">
    <article
      v-for="(hit, idx) in hits"
      :key="idx"
      class="hit-row"
      :class="{ expanded: isExpanded(idx) }"
      role="button"
      tabindex="0"
      @click="toggle(idx)"
      @keydown.enter.prevent="toggle(idx)"
      @keydown.space.prevent="toggle(idx)"
    >
      <header class="hit-row-head">
        <MetricBadge :value="`#${idx + 1}`" />
        <span class="hit-doc">{{ hit.docName }}</span>
        <MetricBadge :value="hit.score.toFixed(4)" />
      </header>
      <div class="hit-row-scroll">
        <div class="hit-content">{{ hit.content }}</div>
      </div>
    </article>
  </div>
</template>

<style scoped>
.final-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
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
