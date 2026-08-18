<script setup lang="ts">
import { computed } from 'vue'
import type { MessageUsage } from '../../api/chat'
import { usageCardOpen } from '../../composables/usageCardBus'

/**
 * 上下文用量展示（类 Cursor Context Usage）：
 * mode=ring 挂模型选择器右侧（触发器）；mode=card 挂输入框上方吸附位（分组卡片）。
 */
const props = defineProps<{
  usage?: MessageUsage | null
  mode?: 'ring' | 'card'
}>()

const GROUP_DEFS: { key: string; label: string; color: string }[] = [
  { key: 'system', label: '系统提示词', color: '#9ca3af' },
  { key: 'rules', label: '用户规则', color: '#86efac' },
  { key: 'skills', label: '技能·模式', color: '#eab308' },
  { key: 'tools', label: '工具定义', color: '#a78bfa' },
  { key: 'contextLayers', label: '上下文层', color: '#3b82f6' },
  { key: 'messages', label: '对话消息', color: '#fb923c' },
  { key: 'other', label: '其他', color: '#f87171' },
]

function fmtK(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}K` : String(n)
}

const percent = computed(() => props.usage?.contextPercent ?? null)

const level = computed(() => {
  const p = percent.value
  if (p == null) return ''
  if (p > 85) return 'usage-ring--error'
  if (p >= 60) return 'usage-ring--warn'
  return ''
})

// SVG 圆环：r=7（16×16 viewBox），周长 2πr
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
  // 后端按上下文实际位置定序下发；前端保持收到顺序渲染，仅补标签/颜色与残差 other
  const merged: Record<string, number> = { ...g, other: Math.max(0, total - sum) }
  return Object.entries(merged)
    .map(([key, v]) => {
      const def = GROUP_DEFS.find(d => d.key === key)
      return { key, label: def?.label ?? key, color: def?.color ?? '#6b7280', tokens: Math.max(0, v) }
    })
    .filter(r => r.tokens > 0)
})

// 堆叠条分段：各组占 contextTokens 比例
const barSegments = computed(() => {
  const total = props.usage?.contextTokens ?? 0
  if (total <= 0) return []
  return groupRows.value.map(r => ({
    color: r.color,
    width: `${Math.max(0.5, (r.tokens / total) * 100)}%`,
  }))
})
</script>

<template>
  <button
    v-if="usage && (props.mode ?? 'ring') === 'ring'"
    type="button"
    class="usage-ring"
    :class="level"
    :title="percent != null ? `上下文已用 ${percent}%` : '上下文用量'"
    @click="usageCardOpen = !usageCardOpen"
  >
    <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
      <circle class="usage-ring-track" cx="8" cy="8" :r="RADIUS" fill="none" stroke-width="2" />
      <circle class="usage-ring-arc" cx="8" cy="8" :r="RADIUS" fill="none" stroke-width="2"
        stroke-linecap="round" :stroke-dasharray="CIRCUMFERENCE" :stroke-dashoffset="dashOffset"
        transform="rotate(-90 8 8)" />
    </svg>
    <span v-if="percent != null" class="usage-ring-pct">{{ percent }}%</span>
  </button>

  <div v-else-if="usage && usageCardOpen" class="usage-card">
    <div class="usage-card-head">
      <span class="usage-card-title">Context Usage</span>
      <button type="button" class="usage-card-close" title="关闭" @click="usageCardOpen = false">
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="4" y1="4" x2="12" y2="12"/><line x1="12" y1="4" x2="4" y2="12"/></svg>
      </button>
    </div>
    <div class="usage-card-sub">
      <span>{{ percent ?? 0 }}% Full</span>
      <span>~{{ fmtK(usage.contextTokens ?? 0) }} / {{ usage.contextWindowTokens ? fmtK(usage.contextWindowTokens) : '?' }} Tokens</span>
    </div>
    <div class="usage-bar">
      <span v-for="(seg, i) in barSegments" :key="i" :style="{ width: seg.width, background: seg.color }" />
    </div>
    <div class="usage-rows">
      <div v-for="row in groupRows" :key="row.key" class="usage-row">
        <span class="usage-dot" :style="{ background: row.color }" />
        <span class="usage-row-label">{{ row.label }}</span>
        <span class="usage-row-val">~{{ fmtK(row.tokens) }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.usage-ring {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  padding: 2px;
  color: var(--sun-text-muted, rgba(255, 255, 255, 0.6));
  cursor: pointer;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.usage-ring-track { stroke: rgba(255, 255, 255, 0.18); }
.usage-ring-arc { stroke: var(--sun-text-secondary, #d4d4d4); }
.usage-ring--warn .usage-ring-arc { stroke: var(--warning-color, #f0a020); }
.usage-ring--error .usage-ring-arc { stroke: var(--error-color, #de5762); }
.usage-ring--warn { color: var(--warning-color, #f0a020); }
.usage-ring--error { color: var(--error-color, #de5762); }

/* 吸附卡片：与 taskboard 卡片同体系（--sun-black + 边框 + 圆角） */
.usage-card {
  min-width: 320px;
  margin-bottom: 8px;
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-black);
  box-shadow: var(--composer-shadow);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.usage-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.usage-card-title {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-secondary);
}
.usage-card-close {
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  padding: 2px;
  color: var(--sun-text-muted);
  cursor: pointer;
}
.usage-card-sub {
  display: flex;
  justify-content: space-between;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
}
.usage-bar {
  display: flex;
  height: 6px;
  border-radius: 3px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.08);
}
.usage-bar span { display: block; height: 100%; }
.usage-rows { display: flex; flex-direction: column; gap: 6px; }
.usage-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}
.usage-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}
.usage-row-label { flex: 1; min-width: 0; }
.usage-row-val { font-variant-numeric: tabular-nums; }
</style>
