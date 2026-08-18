<script setup lang="ts">
import { computed } from 'vue'
import { NPopover } from 'naive-ui'
import type { MessageUsage } from '../../api/chat'

/** 上下文用量圆环（类 Cursor）：模型选择器旁，click 弹分组面板 */
const props = defineProps<{
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

const percent = computed(() => props.usage?.contextPercent ?? null)

const level = computed(() => {
  const p = percent.value
  if (p == null) return ''
  if (p > 85) return 'usage-ring--error'
  if (p >= 60) return 'usage-ring--warn'
  return ''
})

// SVG 圆环：周长 2πr，r=7（16×16 viewBox）
const RADIUS = 7
const CIRCUMFERENCE = 2 * Math.PI * RADIUS
const dashOffset = computed(() => {
  const p = Math.min(100, Math.max(0, percent.value ?? 0))
  return CIRCUMFERENCE * (1 - p / 100)
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
  <NPopover v-if="usage" trigger="click" placement="top-end">
    <template #trigger>
      <button type="button" class="usage-ring" :class="level" :title="percent != null ? `上下文已用 ${percent}%` : '上下文用量'">
        <svg width="16" height="16" viewBox="0 0 16 16">
          <circle class="usage-ring-track" cx="8" cy="8" :r="RADIUS" fill="none" stroke-width="2" />
          <circle class="usage-ring-arc" cx="8" cy="8" :r="RADIUS" fill="none" stroke-width="2"
            stroke-linecap="round" :stroke-dasharray="CIRCUMFERENCE" :stroke-dashoffset="dashOffset"
            transform="rotate(-90 8 8)" />
        </svg>
        <span v-if="percent != null" class="usage-ring-pct">{{ percent }}%</span>
      </button>
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
</template>

<style scoped>
.usage-ring {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  padding: 2px;
  color: var(--text-3, rgba(255, 255, 255, 0.55));
  cursor: pointer;
  font-size: 11px;
}
.usage-ring-track { stroke: var(--border-color, rgba(255, 255, 255, 0.15)); }
.usage-ring-arc { stroke: currentColor; }
.usage-ring--warn { color: var(--warning-color, #f0a020); }
.usage-ring--error { color: var(--error-color, #de5762); }
.usage-panel { min-width: 180px; display: flex; flex-direction: column; gap: 4px; }
.usage-panel-total { font-weight: 600; border-bottom: 1px solid var(--border-color, rgba(255, 255, 255, 0.12)); padding-bottom: 4px; }
.usage-panel-row { display: flex; justify-content: space-between; gap: 16px; }
</style>
