<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  formatDuration,
  formatStepMetadata,
  formatRewriteMetadata,
  parseLoadedSkillLabel,
  resolveStepDurationMs,
  resolveStepExpandBody,
  resolveStepExpandSummary,
  stepLifecycle,
  stripLoadedSkillPrefix,
} from '../../api/processingSteps'
import { formatPlanNodeType } from '../../api/executionPlans'
import PlanNodeIcon from './PlanNodeIcon.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'

const { state, close, drawerWidth, canResizeDrawer, onResizePointerDown } = usePlanNodeDrawer()

const node = computed(() => state.node)
const step = computed(() => state.step)

const title = computed(() => node.value?.label ?? '节点详情')
const typeLabel = computed(() => formatPlanNodeType(node.value?.type ?? ''))
const nodeType = computed(() => node.value?.type ?? 'node')

const statusLabel = computed(() => {
  const s = node.value?.status
  if (s === 'running') return '执行中'
  if (s === 'done') return '已完成'
  if (s === 'error') return '失败'
  return '等待中'
})

const durationText = computed(() => {
  const ms = step.value ? resolveStepDurationMs(step.value) : node.value?.durationMs
  return ms != null ? formatDuration(ms) : ''
})

const summary = computed(() => {
  if (step.value) {
    return resolveStepExpandSummary(step.value) || formatStepMetadata(step.value) || ''
  }
  return node.value?.summary ?? ''
})

const analysisContent = computed(() => {
  const t = node.value?.type
  if (t === 'llm' || t === 'answer') {
    return step.value?.reasoning?.trim() || ''
  }
  return ''
})

const finalOutput = computed(() => {
  if (node.value?.type !== 'answer') return ''
  return step.value?.result?.trim() || ''
})

const body = computed(() => {
  const t = node.value?.type
  if (t === 'llm') {
    return analysisContent.value
  }
  if (t === 'answer') {
    return finalOutput.value
  }
  if (step.value) return resolveStepExpandBody(step.value)
  return node.value?.detail?.trim() ?? ''
})
const bodyDisplay = computed(() => stripLoadedSkillPrefix(body.value))

const analysisDisplay = computed(() => stripLoadedSkillPrefix(analysisContent.value))

const bodySectionTitle = computed(() => {
  const t = node.value?.type
  if (t === 'answer') return '最终输出'
  if (t === 'llm') return '思考过程'
  return '详细输出'
})

const showAnalysisSection = computed(() =>
  (node.value?.type === 'answer' || node.value?.type === 'llm')
  && !!analysisDisplay.value,
)

const showSummary = computed(() => {
  if (node.value?.type === 'answer' || node.value?.type === 'llm') return false
  return !!summary.value.trim()
})
const rewrite = computed(() => (step.value ? formatRewriteMetadata(step.value) : ''))
const showReasoningSection = computed(() =>
  node.value?.type !== 'llm'
  && node.value?.type !== 'answer'
  && !!step.value?.reasoning?.trim(),
)
const reasoning = computed(() => step.value?.reasoning?.trim() ?? '')
const output = computed(() => step.value?.output?.trim() ?? '')

const loadedSkillId = computed(() => node.value?.skillId?.trim() || undefined)

const loadedSkillLabel = computed(() => {
  for (const source of [step.value?.detail, body.value, step.value?.output]) {
    const parsed = parseLoadedSkillLabel(source)
    if (parsed) return parsed
  }
  return node.value?.skillLabel
})

const showSkillBlock = computed(() => !!(loadedSkillId.value || loadedSkillLabel.value))

const bodyRef = ref<HTMLElement | null>(null)
const drawerScrollTop = ref(0)

function onDrawerBodyScroll() {
  drawerScrollTop.value = bodyRef.value?.scrollTop ?? 0
}

async function restoreDrawerScroll() {
  const top = drawerScrollTop.value
  await nextTick()
  if (bodyRef.value) bodyRef.value.scrollTop = top
}

watch(
  () => [state.open, state.node?.id] as const,
  ([open, nodeId], [, prevId]) => {
    if (!open) return
    if (nodeId !== prevId) {
      drawerScrollTop.value = 0
      void nextTick(() => bodyRef.value?.scrollTo(0, 0))
    }
  },
)

watch(
  () => [bodyDisplay.value, analysisDisplay.value, summary.value, reasoning.value, output.value],
  () => {
    if (!state.open) return
    void restoreDrawerScroll()
  },
)
</script>

<template>
  <aside
    v-if="state.open && node"
    class="plan-drawer"
    role="complementary"
    aria-label="节点详情"
    :style="{ width: `${drawerWidth}px` }"
  >
    <div
      v-if="canResizeDrawer"
      class="drawer-resize-handle"
      role="separator"
      aria-orientation="vertical"
      aria-label="调整抽屉宽度"
      @pointerdown="onResizePointerDown"
    />
    <header class="drawer-header">
      <div class="drawer-head-top">
        <div class="drawer-title-row">
          <span class="drawer-type-icon" aria-hidden="true">
            <PlanNodeIcon :type="nodeType" :size="16" />
          </span>
          <h3 class="drawer-title">{{ title }}</h3>
        </div>
        <button type="button" class="drawer-close" aria-label="关闭" @click="close">×</button>
      </div>

      <div class="drawer-status-row">
        <div class="drawer-status-left">
          <span class="meta-type">{{ typeLabel }}</span>
          <span class="meta-status" :class="`is-${node.status}`">
            <span class="status-dot" aria-hidden="true" />
            {{ statusLabel }}
          </span>
        </div>
        <span v-if="durationText" class="meta-dur">{{ durationText }}</span>
      </div>

      <div v-if="showSkillBlock" class="drawer-skill-row">
        <span class="skill-label">已加载技能</span>
        <div class="skill-line">
          <code v-if="loadedSkillId" class="skill-id">{{ loadedSkillId }}</code>
          <template v-if="loadedSkillLabel && loadedSkillLabel !== loadedSkillId">
            <span v-if="loadedSkillId" class="skill-sep" aria-hidden="true">·</span>
            <span class="skill-value">{{ loadedSkillLabel }}</span>
          </template>
          <span v-else-if="loadedSkillLabel && !loadedSkillId" class="skill-value">{{ loadedSkillLabel }}</span>
        </div>
      </div>
    </header>
    <div ref="bodyRef" class="drawer-body" @scroll="onDrawerBodyScroll">
      <div v-if="node.attempts?.length" class="drawer-section">
        <h4>执行记录（{{ node.attemptCount ?? node.attempts.length }} 次）</h4>
        <ul class="attempt-list">
          <li v-for="a in node.attempts" :key="a.attemptNo" class="attempt-item">
            <span class="attempt-no">#{{ a.attemptNo }}</span>
            <span class="attempt-status" :class="`is-${a.status}`">{{ a.status }}</span>
            <span v-if="a.summary" class="attempt-summary">{{ a.summary }}</span>
          </li>
        </ul>
      </div>
      <section v-if="showSummary" class="drawer-section">
        <h4>执行摘要</h4>
        <StaticMarkdown :source="summary" compact />
      </section>
      <section v-if="rewrite" class="drawer-section">
        <h4>检索优化</h4>
        <StaticMarkdown :source="rewrite" compact />
      </section>
      <section v-if="showAnalysisSection" class="drawer-section">
        <h4>综合分析</h4>
        <StaticMarkdown :source="analysisDisplay" compact />
      </section>
      <section v-if="bodyDisplay && bodyDisplay !== summary" class="drawer-section">
        <h4>{{ bodySectionTitle }}</h4>
        <StaticMarkdown :source="bodyDisplay" compact />
      </section>
      <section v-if="showReasoningSection" class="drawer-section">
        <h4>推理过程</h4>
        <StaticMarkdown :source="reasoning" compact />
      </section>
      <section v-if="output" class="drawer-section">
        <h4>日志</h4>
        <StaticMarkdown :source="output" compact />
      </section>
      <p v-if="!showSummary && !showAnalysisSection && !bodyDisplay && !showReasoningSection && !output && !showSkillBlock" class="drawer-empty">
        {{ stepLifecycle(step ?? { id: '', phase: 'node', lifecycle: node.status === 'running' ? 'running' : 'pending' }) === 'running' ? '节点执行中…' : '暂无详情' }}
      </p>
    </div>
  </aside>
</template>

<style scoped>
.plan-drawer {
  position: relative;
  flex-shrink: 0;
  height: 100%;
  min-height: 0;
  z-index: 120;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--sun-border);
  background: var(--sun-bg);
  box-shadow: -8px 0 24px color-mix(in srgb, black 8%, transparent);
}

.drawer-resize-handle {
  position: absolute;
  left: -5px;
  top: 0;
  bottom: 0;
  width: 10px;
  z-index: 5;
  cursor: col-resize;
  touch-action: none;
}

.drawer-resize-handle::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 0;
  bottom: 0;
  width: 2px;
  border-radius: 1px;
  background: transparent;
  transition: background 0.15s;
}

.drawer-resize-handle:hover::after {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 55%, transparent);
}

:global(body.plan-drawer-resizing) {
  cursor: col-resize !important;
  user-select: none !important;
}

:global(body.plan-drawer-resizing .drawer-resize-handle::after) {
  background: var(--sun-blue, #58a6ff);
}

.drawer-header {
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-bg);
}

.drawer-head-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.drawer-title-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.drawer-type-icon {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  border: 1px solid var(--sun-border);
  background: color-mix(in srgb, var(--sun-bg) 88%, var(--sun-text-muted));
  color: var(--sun-text-secondary);
}

.drawer-title {
  margin: 0;
  padding-top: 2px;
  font-size: var(--sun-font-lg);
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.35;
  word-break: break-word;
}

.drawer-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.drawer-status-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.meta-type {
  font-size: 11px;
  font-weight: 500;
  color: var(--sun-text-muted);
  letter-spacing: 0.02em;
}

.meta-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 500;
  color: var(--sun-text-muted);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}

.meta-status.is-pending { color: var(--sun-text-muted); }
.meta-status.is-running { color: var(--sun-blue, #58a6ff); }
.meta-status.is-done { color: var(--sun-green, #3fb950); }
.meta-status.is-error { color: var(--sun-red, #f85149); }

.meta-dur {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--sun-text-secondary);
  font-variant-numeric: tabular-nums;
}

.drawer-skill-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid color-mix(in srgb, var(--sun-border) 90%, var(--sun-gold));
  background: color-mix(in srgb, var(--sun-gold-glow) 40%, var(--sun-row-hover, var(--sun-surface)));
}

.skill-label {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--sun-text-muted);
}

.skill-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.skill-sep {
  color: var(--sun-text-muted);
  opacity: 0.55;
  user-select: none;
}

.skill-id {
  display: inline-block;
  flex-shrink: 0;
  max-width: 100%;
  padding: 1px 7px;
  border-radius: 6px;
  border: 1px solid var(--sun-border);
  background: color-mix(in srgb, var(--sun-bg) 90%, var(--sun-text-muted));
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.45;
  color: var(--sun-text);
}

.skill-value {
  font-size: var(--sun-font-sm);
  font-weight: 450;
  line-height: 1.45;
  color: var(--sun-text-secondary);
  word-break: break-word;
}

.drawer-close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.drawer-close:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 16px 16px 28px;
}

.drawer-section {
  margin-bottom: 16px;
}

.attempt-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.attempt-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.attempt-no {
  font-weight: 600;
  color: var(--sun-text-muted);
}

.attempt-status.is-failed {
  color: #f87171;
}

.attempt-status.is-completed {
  color: #4ade80;
}

.drawer-section h4 {
  margin: 0 0 6px;
  font-size: var(--sun-font-sm);
  font-weight: 600;
  color: var(--sun-text-secondary);
}

.drawer-section :deep(.static-md-compact) {
  color: var(--sun-text-muted);
}

.drawer-empty {
  margin: 24px 0 0;
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  text-align: center;
}
</style>
