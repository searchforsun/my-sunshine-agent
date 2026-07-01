<script setup lang="ts">
import { ref } from 'vue'
import { NText } from 'naive-ui'
import MetricBadge from './MetricBadge.vue'

export interface DebugHit {
  docName: string
  content: string
  score: number
}

defineProps<{
  hits: DebugHit[]
}>()

const expanded = ref<Set<number>>(new Set([0]))

function toggle(idx: number) {
  const next = new Set(expanded.value)
  if (next.has(idx)) {
    next.delete(idx)
  } else {
    next.add(idx)
  }
  expanded.value = next
}

function preview(content: string, max = 96): string {
  const text = content.replace(/\s+/g, ' ').trim()
  if (text.length <= max) return text
  return `${text.slice(0, max)}…`
}
</script>

<template>
  <div class="final-panel">
    <article
      v-for="(hit, idx) in hits"
      :key="idx"
      class="final-row"
      :class="{ expanded: expanded.has(idx) }"
    >
      <button type="button" class="final-row-head" @click="toggle(idx)">
        <MetricBadge :value="`#${idx + 1}`" />
        <span class="final-doc">{{ hit.docName }}</span>
        <MetricBadge :value="hit.score.toFixed(4)" />
        <NText depth="3" class="expand-hint">{{ expanded.has(idx) ? '收起' : '展开' }}</NText>
      </button>
      <p v-if="!expanded.has(idx)" class="final-preview">{{ preview(hit.content) }}</p>
      <div v-else class="final-content">{{ hit.content }}</div>
    </article>
  </div>
</template>

<style scoped>
.final-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.final-row {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  overflow: hidden;
}

.final-row.expanded {
  box-shadow: inset 0 0 0 1px var(--sun-border-light);
}

.final-row-head {
  width: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.final-doc {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expand-hint {
  font-size: 11px;
}

.final-preview {
  margin: 0;
  padding: 0 10px 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.final-content {
  padding: 0 10px 10px;
  font-size: var(--sun-font-base, 14px);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text);
  border-top: 1px solid var(--sun-border);
  padding-top: 10px;
  margin: 0 10px 10px;
}
</style>
