<script setup lang="ts">
import { computed } from 'vue'
import { NPopover } from 'naive-ui'
import type { MessageUsage } from '../../api/chat'

const props = defineProps<{
  turn: number
  usage?: MessageUsage | null
}>()

const GROUP_LABELS: Record<string, string> = {
  system: '系统提示词',
  rules: '用户规则',
  skills: '技能·模式',
  tools: '工具定义',
  contextLayers: '上下文层',
  messages: '对话消息',
  other: '其他',
}

function fmtK(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n)
}

const ctxLabel = computed(() => {
  const u = props.usage
  if (!u) return ''
  if (u.contextPercent != null) return `ctx ${u.contextPercent}%`
  if (u.contextTokens != null) return `ctx ${fmtK(u.contextTokens)}`
  return ''
})

const ctxLevel = computed(() => {
  const p = props.usage?.contextPercent
  if (p == null) return ''
  if (p > 85) return 'usage--error'
  if (p >= 60) return 'usage--warn'
  return ''
})

const groupRows = computed(() => {
  const g = props.usage?.groups
  if (!g) return []
  const total = props.usage?.contextTokens ?? 0
  const sum = Object.values(g).reduce((a, b) => a + Math.max(0, b), 0)
  const other = Math.max(0, total - sum)
  const rows = Object.entries(g)
    .filter(([k]) => k !== 'other')
    .map(([k, v]) => ({ label: GROUP_LABELS[k] ?? k, tokens: Math.max(0, v) }))
  if (other > 0) rows.push({ label: GROUP_LABELS.other, tokens: other })
  return rows.filter(r => r.tokens > 0)
})
</script>

<template>
  <div class="usage-status" :class="ctxLevel">
    <span class="usage-turn">T{{ turn }}</span>
    <template v-if="usage">
      <span class="usage-sep">·</span>
      <span>↑ {{ fmtK(usage.inputTokens) }}</span>
      <span>↓ {{ fmtK(usage.outputTokens) }}</span>
      <template v-if="ctxLabel">
        <NPopover v-if="groupRows.length" trigger="click" placement="top">
          <template #trigger>
            <button type="button" class="usage-ctx-btn">{{ ctxLabel }}</button>
          </template>
          <div class="usage-panel">
            <div class="usage-panel-total">
              ~{{ fmtK(usage.contextTokens ?? 0) }}<template v-if="usage.contextWindowTokens"> / {{ fmtK(usage.contextWindowTokens) }}</template>
            </div>
            <div v-for="row in groupRows" :key="row.label" class="usage-panel-row">
              <span>{{ row.label }}</span>
              <span>~{{ fmtK(row.tokens) }}</span>
            </div>
          </div>
        </NPopover>
        <span v-else>{{ ctxLabel }}</span>
      </template>
    </template>
  </div>
</template>

<style scoped>
.usage-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  height: 24px;
  border: 1px solid var(--border-color, rgba(255, 255, 255, 0.12));
  border-radius: 6px;
  font-size: 12px;
  color: var(--text-3, rgba(255, 255, 255, 0.55));
  white-space: nowrap;
}
.usage-status.usage--warn { color: var(--warning-color, #f0a020); border-color: var(--warning-color, #f0a020); }
.usage-status.usage--error { color: var(--error-color, #de5762); border-color: var(--error-color, #de5762); }
.usage-ctx-btn {
  border: none;
  background: transparent;
  padding: 0;
  font: inherit;
  color: inherit;
  cursor: pointer;
}
.usage-panel { min-width: 180px; display: flex; flex-direction: column; gap: 4px; }
.usage-panel-total { font-weight: 600; border-bottom: 1px solid var(--border-color, rgba(255, 255, 255, 0.12)); padding-bottom: 4px; }
.usage-panel-row { display: flex; justify-content: space-between; gap: 16px; }
</style>
