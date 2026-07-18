<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  resolveStepDurationMs,
  formatStepLabel,
  stepLifecycle,
  resolveStepHeaderText,
  resolveStepExpandPanels,
  shouldShiftSummaryOnExpand,
  hasExpandableContent,
  resolvePlanIdFromStep,
  resolveSandboxFocusPath,
} from '../../api/processingSteps'
import { useRouter } from 'vue-router'
import StaticMarkdown from '../StaticMarkdown.vue'
import { isToolStepId, type HitlConfirmationPayload } from '../../api/hitlSteps'
import HitlStepActions from './HitlStepActions.vue'
import SandboxToolExpandPanel from './SandboxToolExpandPanel.vue'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'
import { useSandboxToolExpand } from '../../composables/useSandboxToolExpand'
import { useChatStore } from '../../stores/chatStore'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  expanded: boolean
  live?: boolean
  /** 消息级 executionPlanId 兜底（历史数据） */
  executionPlanId?: string
  /** 为 false 时不在卡片内嵌 HITL（Plan 抽屉等外层承载） */
  embedHitl?: boolean
  pendingHitlConfirmation?: HitlConfirmationPayload
  hitlUiKey?: string
}>(), {
  embedHitl: true,
  pendingHitlConfirmation: undefined,
  hitlUiKey: '',
})

const router = useRouter()
const chatStore = useChatStore()
const sandboxDrawer = useSandboxWorkspaceDrawer()

const emit = defineEmits<{
  toggle: []
  hitlDecided: [token: string, approved: boolean]
}>()

function onRowActivate() {
  if (isSandboxTool.value && chatStore.currentId) {
    const focus = resolveSandboxFocusPath(props.step)
    sandboxDrawer.open({
      conversationId: chatStore.currentId,
      focusPath: focus,
    })
  }
  if (canExpand.value) {
    emit('toggle')
  }
}

function openSandboxPath(path: string) {
  if (!chatStore.currentId || !path) return
  sandboxDrawer.open({
    conversationId: chatStore.currentId,
    focusPath: path,
  })
}

const showEmbeddedHitl = computed(() =>
  props.embedHitl !== false && isToolStepId(props.step.id),
)

const hitlPanelKey = computed(() =>
  props.hitlUiKey
  || props.step.metadata?.hitlToken
  || props.step.metadata?.hitlStatus
  || props.pendingHitlConfirmation?.confirmationToken
  || props.step.summary?.active
  || props.step.id,
)

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const label = computed(() => formatStepLabel(props.step))

/** 主行摘要：折叠时一行预览；展开且可下移时主行仅保留 label */
const headerText = computed(() => resolveStepHeaderText(props.step))
const shiftSummary = computed(() => shouldShiftSummaryOnExpand(props.step))
const { isSandboxTool, editDiffSummary } = useSandboxToolExpand(() => props.step)

const showHeaderPreview = computed(
  () => !!headerText.value && (!props.expanded || !shiftSummary.value),
)

const expandPanels = computed(() => resolveStepExpandPanels(props.step))
const expandSummary = computed(() => expandPanels.value.lead)
const expandBody = computed(() => expandPanels.value.body)

const canExpand = computed(() => hasExpandableContent(props.step))
const rowClickable = computed(() => canExpand.value || isSandboxTool.value)

const planLinkId = computed(() => {
  if (props.step.phase !== 'plan') return undefined
  return resolvePlanIdFromStep(props.step) ?? props.executionPlanId
})

function openPlanDetail() {
  const id = planLinkId.value
  if (!id) return
  void router.push({ name: 'plan-detail', params: { planId: id } })
}

const liveElapsedMs = ref<number | null>(null)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

function clearElapsedTimer() {
  if (elapsedTimer != null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

watch(
  () => [props.live, isRunning.value, props.step.startedAt] as const,
  ([live, running, startedAt]) => {
    clearElapsedTimer()
    if (live && running && typeof startedAt === 'number') {
      const tick = () => {
        liveElapsedMs.value = Math.max(0, Date.now() - startedAt)
      }
      tick()
      elapsedTimer = setInterval(tick, 200)
    } else {
      liveElapsedMs.value = null
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  clearElapsedTimer()
})

const durationText = computed(() => {
  if (isDone.value) {
    const ms = resolveStepDurationMs(props.step)
    if (ms != null) return formatDuration(ms)
  }
  if (isRunning.value && props.live && liveElapsedMs.value != null) {
    return formatDuration(liveElapsedMs.value)
  }
  return ''
})

const showShimmer = computed(() => isRunning.value && !!props.live)
</script>

<template>
  <div
    class="op-line"
    :class="{
      'is-expanded': expanded,
      'is-running': isRunning && live,
      'is-clickable': rowClickable,
    }"
  >
    <div
      class="op-line-row"
      :role="rowClickable ? 'button' : undefined"
      :tabindex="rowClickable ? 0 : -1"
      @click="onRowActivate"
      @keydown.enter.prevent="onRowActivate"
      @keydown.space.prevent="onRowActivate"
    >
      <span class="op-gutter" aria-hidden="true">
        <svg
          v-if="canExpand"
          class="op-chevron"
          width="9"
          height="9"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </span>
      <span class="op-main">
        <span
          class="op-label operation-card-title"
          :class="{ 'op-shimmer': showShimmer }"
        >{{ label }}</span>
        <span
          v-if="showHeaderPreview"
          class="op-text"
          :class="{ 'op-shimmer': showShimmer }"
        >
          {{ headerText }}
          <span v-if="editDiffSummary" class="op-diff-summary" aria-label="变更行数">
            <span v-if="editDiffSummary.add" class="op-diff-stat is-add">+{{ editDiffSummary.add }}</span>
            <span v-if="editDiffSummary.del" class="op-diff-stat is-del">-{{ editDiffSummary.del }}</span>
          </span>
          <span v-if="isRunning && live" class="op-pulse">…</span>
        </span>
      </span>
      <span v-if="durationText" class="op-dur">{{ durationText }}</span>
      <button
        v-if="planLinkId"
        type="button"
        class="op-plan-link"
        @click.stop="openPlanDetail"
      >
        查看详情
      </button>
    </div>

    <HitlStepActions
      v-if="showEmbeddedHitl"
      :key="hitlPanelKey"
      :step="step"
      :pending-confirmation="pendingHitlConfirmation"
      @decided="(token, approved) => emit('hitlDecided', token, approved)"
    />

    <div v-if="expanded && canExpand" class="op-detail">
      <SandboxToolExpandPanel
        v-if="isSandboxTool"
        :step="step"
        @open-path="openSandboxPath"
      />
      <template v-else>
        <div v-if="expandSummary && shiftSummary" class="op-detail-after">
          <StaticMarkdown :source="expandSummary" compact />
        </div>
        <StaticMarkdown v-if="expandBody" :source="expandBody" compact />
        <div v-if="step.reasoning?.trim()" class="op-detail-thinking">
          <StaticMarkdown :source="step.reasoning" compact />
        </div>
        <StaticMarkdown v-if="step.output?.trim()" :source="step.output" compact />
      </template>
    </div>
  </div>
</template>

<style scoped>
.op-line {
  --op-gutter: 12px;
  --op-detail-inset: calc(var(--op-gutter) + 4px);
  --op-font: var(--sun-font-md);
  --op-font-sm: var(--sun-font-sm);
  --op-detail-font: var(--sun-font-base);
  font-size: var(--op-font);
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.op-line-row {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr) auto auto;
  column-gap: 4px;
  align-items: start;
  width: 100%;
  padding: 1px 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: default;
}

.op-line.is-clickable .op-line-row {
  cursor: pointer;
}

.op-line.is-clickable:hover .op-label {
  color: var(--sun-text-secondary);
}

.op-gutter {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  width: var(--op-gutter);
  padding-top: 4px;
  flex-shrink: 0;
}

.op-chevron {
  flex-shrink: 0;
  color: var(--sun-text-muted);
  opacity: 0.5;
  transition: transform 0.15s ease;
}

.op-line.is-expanded .op-chevron {
  transform: rotate(90deg);
}

.op-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.op-shimmer {
  --op-shimmer-base: var(--sun-text-muted);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text-muted) 32%, white);
  display: inline-block;
  max-width: 100%;
  background-image: linear-gradient(
    90deg,
    var(--op-shimmer-base) 0%,
    var(--op-shimmer-base) 36%,
    var(--op-shimmer-peak) 50%,
    var(--op-shimmer-base) 64%,
    var(--op-shimmer-base) 100%
  );
  background-size: 220% 100%;
  background-repeat: no-repeat;
  background-position: 100% center;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: op-text-shimmer 2.6s linear infinite;
  will-change: background-position;
}

.op-label.op-shimmer {
  --op-shimmer-base: var(--sun-text);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text) 22%, white);
}

.op-text.op-shimmer {
  opacity: 1;
}

.op-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.op-text {
  flex: 1 1 0;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.op-dur {
  flex-shrink: 0;
  padding-left: 10px;
  padding-top: 1px;
  font-size: var(--op-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.op-plan-link {
  flex-shrink: 0;
  margin-left: 8px;
  padding: 0 8px;
  height: 22px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: 11px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}

.op-plan-link:hover {
  color: var(--sun-text);
  border-color: var(--sun-border-light);
}

.op-line.is-running .op-label:not(.op-shimmer) {
  color: var(--sun-text);
}

.op-pulse {
  animation: op-pulse 1.2s ease-in-out infinite;
}

.op-detail {
  margin: 2px 0 6px var(--op-detail-inset);
  padding-left: 8px;
  border-left: 1px solid color-mix(in srgb, var(--sun-text-muted) 18%, transparent);
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-height: min(40vh, 320px);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 2px;
}

.op-diff-summary {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  flex-shrink: 0;
  margin-left: 6px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.op-diff-stat.is-add {
  color: #2a9a5c;
}

.op-diff-stat.is-del {
  color: #c44;
}

.op-detail-after {
  opacity: 0.92;
}

.op-detail-after :deep(.static-md-compact.msg-md),
.op-detail-after :deep(.static-md-compact) {
  color: var(--sun-text-muted);
}

.op-detail-thinking {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* 压过 markdown-content.css .msg-md { color: sun-text }，恢复思考区偏灰 */
.op-detail :deep(.static-md-compact.msg-md),
.op-detail :deep(.static-md-compact) {
  color: var(--sun-text-muted);
  opacity: 0.9;
}

.op-line :deep(.collapsible-confirm) {
  --confirm-inset-left: 0;
  margin-left: var(--op-detail-inset);
}

@keyframes op-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

@keyframes op-text-shimmer {
  0% { background-position: 100% center; }
  100% { background-position: 0% center; }
}
</style>
