<script setup lang="ts">
import { computed, inject, nextTick, ref, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { hasHitlPanel } from '../../api/hitlSteps'
import { findHitlStep, isRecoveryAwaiting, isRecoverySkipped } from '../../api/recoverySteps'
import {
  formatDuration,
  parseLoadedSkillLabel,
  resolvePlanStepDetail,
  resolveRewriteDetail,
  resolveStepDurationMs,
  resolveStepExpandBody,
  resolveStepExpandSummary,
  stepLifecycle,
  stripLoadedSkillPrefix,
} from '../../api/processingSteps'
import { formatPlanNodeType } from '../../api/executionPlans'
import type { DagNodeStatus } from '../../utils/planGraph'
import PlanNodeIcon from './PlanNodeIcon.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import HitlStepActions from '../operation/HitlStepActions.vue'
import PlanNodeRecoveryActions from './PlanNodeRecoveryActions.vue'
import OperationStack from '../operation/OperationStack.vue'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'

const { state, close, drawerWidth, canResizeDrawer, onResizePointerDown } = usePlanNodeDrawer()
const applyHitlDecision = inject<(token: string, approved: boolean) => void>('applyHitlDecision', () => {})
const applyRecoveryDecision = inject<(token: string, action: 'retry' | 'terminate' | 'skip') => void>('applyRecoveryDecision', () => {})

const node = computed(() => state.node)
const step = computed(() => state.step)
const userQuery = computed(() => state.userQuery?.trim() ?? '')

const title = computed(() => node.value?.label ?? '节点详情')
const typeLabel = computed(() => formatPlanNodeType(node.value?.type ?? ''))
const nodeType = computed(() => node.value?.type ?? 'node')

/** 优先 step.lifecycle，与 DAG 节点状态对齐 */
const displayStatus = computed((): DagNodeStatus => {
  const stepLc = step.value ? stepLifecycle(step.value) : undefined
  const nodeStatus = node.value?.status
  if (stepLc === 'paused' || nodeStatus === 'paused') return 'paused'
  if (stepLc === 'terminated' || nodeStatus === 'terminated') return 'terminated'
  if (stepLc === 'skipped' || nodeStatus === 'skipped' || (step.value != null && isRecoverySkipped(step.value))) return 'skipped'
  if (nodeStatus === 'awaiting_confirm') return 'awaiting_confirm'
  if (stepLc === 'error' || nodeStatus === 'error') return 'error'
  if (stepLc === 'done' || nodeStatus === 'done') return 'done'
  if (stepLc === 'running' || nodeStatus === 'running') return 'running'
  return nodeStatus ?? 'pending'
})

const statusLabel = computed(() => {
  const s = displayStatus.value
  if (s === 'paused') return '已暂停'
  if (s === 'terminated') return '已终止'
  if (s === 'awaiting_confirm') return '待确认'
  if (s === 'skipped') return '已跳过'
  if (s === 'error' && isRecoveryAwaiting(step.value)) return '发生错误'
  if (s === 'running') return '执行中'
  if (s === 'done') return '已完成'
  if (s === 'error') return '失败'
  return '等待中'
})

const durationText = computed(() => {
  const fromStep = step.value ? resolveStepDurationMs(step.value) : undefined
  const ms = fromStep ?? node.value?.durationMs
  return ms != null ? formatDuration(ms) : ''
})

const summary = computed(() => {
  if (step.value) {
    return resolveStepExpandSummary(step.value) || ''
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
  return step.value?.metadata?.expandSectionTitle?.trim() || '详细输出'
})

const showAnalysisSection = computed(() =>
  (node.value?.type === 'answer' || node.value?.type === 'llm')
  && !!analysisDisplay.value,
)

const showSummary = computed(() => {
  if (node.value?.type === 'start' || node.value?.type === 'answer' || node.value?.type === 'llm') return false
  return !!summary.value.trim()
})
const rewriteDetail = computed(() => (step.value ? resolveRewriteDetail(step.value) : undefined))
const rewriteSectionTitle = computed(() => {
  const scenario = step.value?.metadata?.rewriteScenario
  if (scenario === 'planner') return '输入优化'
  if (scenario === 'intent') return '问句补全'
  return '检索优化'
})
const showRewriteDetail = computed(() => !!rewriteDetail.value)
const startPlan = computed(() => (step.value ? resolvePlanStepDetail(step.value) : { chainSteps: [] }))
const showStartPlan = computed(() => {
  if (node.value?.type !== 'start') return false
  const plan = startPlan.value
  return !!(plan.planId || plan.chainSteps.length || plan.replanCount)
})
const showBodySection = computed(() => {
  if (node.value?.type === 'start') return false
  return !!bodyDisplay.value && bodyDisplay.value !== summary.value
})
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

const skillDetailText = computed(() => {
  const id = loadedSkillId.value
  const label = loadedSkillLabel.value
  if (id && label && label !== id) return `@${id} ${label}`
  if (id) return `@${id}`
  return label || ''
})

const skillLineText = computed(() => {
  const detail = skillDetailText.value
  return detail ? `加载技能 ${detail}` : ''
})

const subSteps = computed(() => step.value?.subSteps ?? [])
const showSubTimeline = computed(() => node.value?.type === 'agent' && subSteps.value.length > 0)
const hitlStep = computed(() => findHitlStep(step.value))
/** 子 Agent HITL 内嵌于 OperationCard；仅误挂 node 自身时栈外兜底 */
const subAgentOrphanHitlStep = computed(() => {
  if (!showSubTimeline.value) return undefined
  const h = hitlStep.value
  if (!h || !hasHitlPanel(h)) return undefined
  return h.id === step.value?.id ? h : undefined
})
/** 独立 tool 节点在抽屉内展示 HITL */
const showHitlSection = computed(() =>
  !!hitlStep.value
  && hasHitlPanel(hitlStep.value!)
  && !showSubTimeline.value,
)
const showRecoverySection = computed(() => !!step.value && isRecoveryAwaiting(step.value))
const subTimelineLive = computed(() => {
  const s = displayStatus.value
  return s === 'running' || s === 'awaiting_confirm'
})

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
  () => [bodyDisplay.value, analysisDisplay.value, summary.value, reasoning.value, output.value, subSteps.value],
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

      <p v-if="userQuery" class="drawer-meta-line" :title="`用户问题 ${userQuery}`">
        <span class="meta-line-label">用户问题</span>
        <span class="meta-line-detail">{{ userQuery }}</span>
      </p>

      <div class="drawer-status-row">
        <div class="drawer-status-left">
          <span class="meta-type">{{ typeLabel }}</span>
          <span class="meta-status" :class="`is-${displayStatus}`">
            <span class="status-dot" aria-hidden="true" />
            {{ statusLabel }}
          </span>
        </div>
        <span v-if="durationText" class="meta-dur">{{ durationText }}</span>
      </div>

      <p v-if="showSkillBlock" class="drawer-meta-line" :title="skillLineText">
        <span class="meta-line-label">加载技能</span>
        <span class="meta-line-detail">{{ skillDetailText }}</span>
      </p>
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
      <section v-if="showRecoverySection && step" class="drawer-section drawer-recovery">
        <PlanNodeRecoveryActions :step="step" @decided="applyRecoveryDecision" />
      </section>
      <section v-if="showSubTimeline" class="drawer-section drawer-sub-timeline">
        <h4>执行过程</h4>
        <OperationStack
          :steps="subSteps"
          :live="subTimelineLive"
          @hitl-decided="applyHitlDecision"
        />
        <HitlStepActions
          v-if="subAgentOrphanHitlStep"
          :step="subAgentOrphanHitlStep"
          @decided="applyHitlDecision"
        />
      </section>
      <section v-if="showHitlSection && hitlStep" class="drawer-section drawer-hitl">
        <h4>写操作确认</h4>
        <HitlStepActions :step="hitlStep" @decided="applyHitlDecision" />
      </section>
      <section v-if="showSummary" class="drawer-section">
        <h4>执行摘要</h4>
        <StaticMarkdown :source="summary" compact />
      </section>
      <section v-if="showRewriteDetail" class="drawer-section">
        <h4>{{ rewriteSectionTitle }}</h4>
        <p class="drawer-meta-line" :title="rewriteDetail!.from">
          <span class="meta-line-label">原问题</span>
          <span class="meta-line-detail">{{ rewriteDetail!.from }}</span>
        </p>
        <p class="drawer-meta-line" :title="rewriteDetail!.to">
          <span class="meta-line-label">{{ rewriteDetail!.targetLabel }}</span>
          <span class="meta-line-detail">{{ rewriteDetail!.to }}</span>
        </p>
        <p v-if="rewriteDetail!.latencyText" class="drawer-meta-line" :title="rewriteDetail!.latencyText">
          <span class="meta-line-label">耗时</span>
          <span class="meta-line-detail">{{ rewriteDetail!.latencyText }}</span>
        </p>
      </section>
      <section v-if="showStartPlan" class="drawer-section">
        <h4>执行计划</h4>
        <p v-if="startPlan.replanCount" class="plan-replan-hint">
          经 {{ startPlan.replanCount }} 次修正后确定
        </p>
        <template v-if="startPlan.chainSteps.length">
          <p
            v-for="(name, index) in startPlan.chainSteps"
            :key="`${index}-${name}`"
            class="drawer-meta-line plan-step-line"
            :title="name"
          >
            <span class="meta-line-label">{{ index + 1 }}</span>
            <span class="meta-line-detail">{{ name }}</span>
          </p>
        </template>
        <p v-if="startPlan.planId" class="drawer-meta-line plan-id-line" :title="startPlan.planId">
          <span class="meta-line-label">Plan ID</span>
          <span class="meta-line-detail plan-id-value">{{ startPlan.planId }}</span>
        </p>
      </section>
      <section v-if="showAnalysisSection" class="drawer-section">
        <h4>综合分析</h4>
        <StaticMarkdown :source="analysisDisplay" compact />
      </section>
      <section v-if="showBodySection" class="drawer-section">
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
      <p v-if="!showSummary && !showRewriteDetail && !showStartPlan && !showAnalysisSection && !showBodySection && !showReasoningSection && !showSubTimeline && !subAgentOrphanHitlStep && !showHitlSection && !showRecoverySection && !output && !showSkillBlock && !node.attempts?.length" class="drawer-empty">
        {{ displayStatus === 'running' ? '节点执行中…' : displayStatus === 'paused' ? '节点已暂停' : '暂无详情' }}
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

.drawer-meta-line {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  font-size: var(--sun-font-md);
  line-height: var(--sun-line-relaxed);
  overflow: hidden;
}

.meta-line-label {
  flex-shrink: 0;
  font-weight: 450;
  color: var(--sun-text-secondary);
}

.meta-line-detail {
  min-width: 0;
  font-weight: 400;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
.meta-status.is-paused { color: #ca8a04; }
.meta-status.is-terminated { color: var(--sun-text-muted); }
.meta-status.is-done { color: var(--sun-green, #3fb950); }
.meta-status.is-awaiting_confirm { color: #d97706; }
.meta-status.is-skipped { color: #0f766e; }
.meta-status.is-error { color: var(--sun-red, #f85149); }

.meta-dur {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--sun-text-secondary);
  font-variant-numeric: tabular-nums;
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
  margin: 0 0 8px;
  font-size: var(--sun-font-sm);
  font-weight: 600;
  color: var(--sun-text-secondary);
}

.drawer-section .drawer-meta-line + .drawer-meta-line {
  margin-top: 6px;
}

.drawer-section .drawer-meta-line {
  align-items: flex-start;
}

.drawer-section .meta-line-detail {
  white-space: pre-wrap;
  word-break: break-word;
  overflow: visible;
  text-overflow: unset;
}

.plan-id-line {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--sun-border);
  align-items: center;
  font-size: var(--sun-font-sm);
  line-height: 1.45;
}

.plan-id-line .meta-line-label,
.plan-id-line .meta-line-detail {
  font-size: inherit;
  line-height: inherit;
}

.plan-id-value {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
}

.plan-replan-hint {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.plan-step-line {
  align-items: center;
}

.plan-step-line .meta-line-label {
  min-width: 1.25rem;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.plan-step-line + .plan-step-line {
  margin-top: 6px;
}

.drawer-section :deep(.static-md-compact) {
  color: var(--sun-text-muted);
}

.drawer-sub-timeline :deep(.operation-lines) {
  margin-left: 0;
  padding-bottom: 0;
}

.drawer-sub-timeline :deep(.hitl-panel),
.drawer-hitl :deep(.hitl-panel) {
  margin-left: 0;
  margin-top: 0;
}

.drawer-sub-timeline :deep(.hitl-panel) {
  margin-top: 10px;
}

.drawer-empty {
  margin: 24px 0 0;
  font-size: var(--sun-font-base);
  color: var(--sun-text-muted);
  text-align: center;
}
</style>
