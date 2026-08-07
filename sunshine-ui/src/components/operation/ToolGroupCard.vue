<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  stepLifecycle,
} from '../../api/processingSteps'
import {
  isHitlAwaiting,
  isHitlSummaryAwaiting,
  hasHitlPanel,
  resolvePendingHitlForStep,
  resolveHitlToken,
  type HitlConfirmationPayload,
} from '../../api/hitlSteps'
import OperationCard from './OperationCard.vue'
import HitlStepActions from './HitlStepActions.vue'

const props = defineProps<{
  steps: ProcessingStep[]
  /** 分组类别：工具调用 / 知识检索 / sandbox 按用途细分（决定组文案） */
  groupKind?: 'tool' | 'rag' | 'sandbox-view' | 'sandbox-edit' | 'sandbox-fetch' | 'sandbox-exec'
  allDone: boolean
  anyRunning: boolean
  live?: boolean
  pendingList?: HitlConfirmationPayload[]
  allSteps?: ProcessingStep[]
  /** exec 步所属轮次 think 摘要（think_summary 工具输出），组内 exec 步主行拼接 */
  summaryByStepId?: Map<string, string>
}>()

const emit = defineEmits<{
  hitlDecided: [token: string, approved: boolean]
}>()

/** 用户手动展开/折叠标记；null 表示未手动干预，跟随自动逻辑 */
const manualExpanded = ref<boolean | null>(null)

/** 组内是否有步骤需要 HITL 确认 */
const hasHitlInGroup = computed(() => {
  return props.steps.some(s =>
    isHitlAwaiting(s) || isHitlSummaryAwaiting(s) || hasHitlPanel(s),
  )
})

/** 自动展开：运行中或 HITL 待确认时展开，完成后收起 */
const autoExpanded = computed(() => hasHitlInGroup.value || props.anyRunning)

function isExpanded(): boolean {
  return manualExpanded.value ?? autoExpanded.value
}

function toggle(): void {
  manualExpanded.value = !isExpanded()
}

/** 组内单个工具卡片的展开状态（受控于 OperationCard 的 toggle 事件） */
const toolCardExpanded = reactive(new Map<string, boolean>())

function isToolCardExpanded(step: ProcessingStep): boolean {
  return toolCardExpanded.get(step.id) ?? false
}

function toggleToolCard(step: ProcessingStep): void {
  toolCardExpanded.set(step.id, !isToolCardExpanded(step))
}

const label = computed(() => {
  const n = props.steps.length
  switch (props.groupKind) {
    case 'rag': return `检索${n}次知识库`
    case 'sandbox-view': return `查看${n}次文件`
    case 'sandbox-edit': return `修改${n}次文件`
    case 'sandbox-fetch': return `查找${n}次网页`
    case 'sandbox-exec': return `执行${n}次命令`
    default: return `调用${n}个工具`
  }
})

const showShimmer = computed(() => props.anyRunning && !!props.live)

function lifecycleOf(step: ProcessingStep) {
  return stepLifecycle(step)
}

function stepHitlToken(step: ProcessingStep): string {
  return resolveHitlToken(step) ?? ''
}

function pendingForStep(step: ProcessingStep): HitlConfirmationPayload | undefined {
  if (!props.pendingList?.length) return undefined
  return resolvePendingHitlForStep(step, props.pendingList, props.allSteps ?? props.steps)
}

function shouldShowInlineHitl(step: ProcessingStep): boolean {
  return hasHitlPanel(step) || isHitlAwaiting(step) || isHitlSummaryAwaiting(step)
}
</script>

<template>
  <div class="tool-group" :class="{ 'is-running': anyRunning, 'is-done': allDone, 'is-expanded': isExpanded() }">
    <div
      class="tool-group-row"
      role="button"
      tabindex="0"
      @click="toggle"
      @keydown.enter.prevent="toggle"
      @keydown.space.prevent="toggle"
    >
      <span class="op-main">
        <span class="tool-group-label" :class="{ 'op-shimmer': showShimmer }">{{ label }}</span>
        <span v-if="allDone" class="tool-group-check" aria-label="完成">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5" />
            <polyline points="4.5 8 7 10.5 11.5 5.5" />
          </svg>
        </span>
        <span v-if="anyRunning && live" class="op-pulse">…</span>
        <svg
          class="op-chevron"
          width="12"
          height="12"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
      </span>
    </div>
    <div v-if="isExpanded()" class="tool-group-body">
      <template v-for="step in steps" :key="step.id">
        <OperationCard
          :step="step"
          :expanded="isToolCardExpanded(step)"
          :live="live && lifecycleOf(step) === 'running'"
          :embed-hitl="false"
          :round-summary="props.summaryByStepId?.get(step.id)"
          @toggle="toggleToolCard(step)"
        />
        <div v-if="shouldShowInlineHitl(step)" class="tool-group-hitl">
          <HitlStepActions
            :key="`${step.id}-${stepHitlToken(step)}`"
            :step="step"
            :pending-confirmation="pendingForStep(step)"
            @decided="(token, approved) => emit('hitlDecided', token, approved)"
          />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tool-group {
  font-size: var(--sun-font-md);
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.tool-group-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  column-gap: 4px;
  align-items: start;
  width: 100%;
  padding: 1px 0;
  cursor: pointer;
}

/* 文字后展开箭头：紧跟组 label，hover / 展开态显示 */
.op-chevron {
  flex-shrink: 0;
  align-self: center;
  width: 12px;
  height: 12px;
  color: var(--sun-text-secondary);
  opacity: 0;
  margin-left: 2px;
  transition: transform 0.15s ease, opacity 0.12s ease;
}

.tool-group-row:hover .op-chevron {
  opacity: 0.85;
}

.tool-group.is-expanded .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}

.op-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.tool-group-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.tool-group.is-running .tool-group-label:not(.op-shimmer) {
  color: var(--sun-text);
}

.tool-group.is-done .tool-group-label {
  color: var(--sun-text-muted);
}

.tool-group-check {
  color: var(--sun-text-muted);
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
}

.tool-group.is-done .tool-group-check {
  color: var(--sun-text-muted);
}

.tool-group-body {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.tool-group-hitl {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
}

.tool-group-hitl :deep(.collapsible-confirm) {
  margin-left: 0;
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
  animation: op-text-shimmer 1.2s linear infinite;
}

.tool-group-label.op-shimmer {
  --op-shimmer-base: var(--sun-text-secondary);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text-secondary) 30%, white);
}

.op-pulse {
  animation: op-pulse 1.2s ease-in-out infinite;
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