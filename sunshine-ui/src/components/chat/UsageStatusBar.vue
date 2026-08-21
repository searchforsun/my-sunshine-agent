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

// 色卡用饱和度中-高的色阶，亮/暗双主题均能与底色（白/黑）形成明显对比；
// 顺序与 UsageJsonSupport.GROUP_ORDER 一致：系统/工具/规则/技能/上下文层/对话/其他。
const GROUP_DEFS: { key: string; label: string; color: string }[] = [
  { key: 'system', label: '系统提示词', color: '#64748b' },
  { key: 'tools', label: '工具定义', color: '#8b5cf6' },
  { key: 'rules', label: '用户规则', color: '#16a34a' },
  { key: 'skills', label: '技能·模式', color: '#d97706' },
  { key: 'contextLayers', label: '上下文层', color: '#2563eb' },
  { key: 'messages', label: '对话消息', color: '#ea580c' },
  { key: 'other', label: '其他', color: '#dc2626' },
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

// 堆叠条分段：各组占总上下文窗口比例（剩余由轨道底色呈现）
const barSegments = computed(() => {
  const window = props.usage?.contextWindowTokens ?? 0
  if (window <= 0) return []
  return groupRows.value.map(r => ({
    color: r.color,
    width: `${Math.max(0.6, (r.tokens / window) * 100)}%`,
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
    <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
      <circle class="usage-ring-track" cx="8" cy="8" :r="RADIUS" fill="none" stroke-width="2" />
      <circle class="usage-ring-arc" cx="8" cy="8" :r="RADIUS" fill="none" stroke-width="2"
        stroke-linecap="round" :stroke-dasharray="CIRCUMFERENCE" :stroke-dashoffset="dashOffset"
        transform="rotate(-90 8 8)" />
    </svg>
  </button>

  <div v-else-if="usage && usageCardOpen" class="usage-card">
    <div class="usage-card-head">
      <span class="usage-card-title">上下文用量</span>
      <button type="button" class="usage-card-close" title="关闭" @click="usageCardOpen = false">
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="4" y1="4" x2="12" y2="12"/><line x1="12" y1="4" x2="4" y2="12"/></svg>
      </button>
    </div>
    <div class="usage-card-sub">
      <span>{{ percent ?? 0 }}%</span>
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
  color: var(--sun-text-muted);
  cursor: pointer;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
/* 轨道/弧段用主题边框色与强调色，双主题均高对比 */
.usage-ring-track { stroke: color-mix(in srgb, var(--sun-border) 60%, transparent); }
.usage-ring-arc { stroke: var(--sun-accent); }
.usage-ring--warn .usage-ring-arc { stroke: var(--warning-color, #f0a020); }
.usage-ring--error .usage-ring-arc { stroke: var(--error-color, #de5762); }
.usage-ring--warn { color: var(--warning-color, #f0a020); }
.usage-ring--error { color: var(--error-color, #de5762); }

/* 吸附卡片：与 taskboard 卡片同体系（--sun-black + 边框），圆角对齐输入框 */
.usage-card {
  min-width: 320px;
  margin-bottom: 8px;
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg, 12px);
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
  background: var(--sun-border);
  border: 1px solid var(--sun-border);
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
  /* 1px 主题边框双主题兜底，避免亮色下浅色色块与白底融在一起 */
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--sun-border) 70%, transparent);
}
.usage-row-label { flex: 1; min-width: 0; }
.usage-row-val { font-variant-numeric: tabular-nums; }
</style>
