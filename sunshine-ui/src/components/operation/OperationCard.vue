<script setup lang="ts">
import { computed, inject, onUnmounted, ref, watch } from 'vue'
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
  resolveSandboxReadLineRange,
  isSandboxReadStep,
  isSandboxExecStep,
  isSandboxFetchStep,
  formatExecCommandHeaderText,
  extractSandboxExecCommand,
  isCancellableSandboxTool,
} from '../../api/processingSteps'
import {
  resolveIntentRoutingTraces,
} from '../../api/processingSteps'
import { isThinkStepId } from '../../api/processingStepsNormalize'
import { useRouter } from 'vue-router'
import StaticMarkdown from '../StaticMarkdown.vue'
import { isToolStepId, isHitlSummaryAwaiting } from '../../api/hitlSteps'
import HitlStepActions from './HitlStepActions.vue'
import TimelineStepIcon from './TimelineStepIcon.vue'
import SandboxToolExpandPanel from './SandboxToolExpandPanel.vue'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'
import { useSandboxToolExpand } from '../../composables/useSandboxToolExpand'
import { useTimelineStyle } from '../../composables/useTimelineStyle'
import { useChatStore } from '../../stores/chatStore'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  expanded: boolean
  live?: boolean
  /** 消息级 executionPlanId 兜底（历史数据） */
  executionPlanId?: string
  /** 为 false 时不在卡片内嵌 HITL（Plan 抽屉等外层承载） */
  embedHitl?: boolean
  /** 整段时间线折叠预览：不显示 chevron、不可点开详情 */
  hideChevron?: boolean
  hitlUiKey?: string
  /** exec 步所属轮次 think 摘要（think_summary 工具输出），主行「执行命令 {摘要} {命令头}」 */
  roundSummary?: string
  /** 折叠区内不显示 ✓ */
  hideCheckmark?: boolean
}>(), {
  embedHitl: true,
  hideChevron: false,
  hitlUiKey: '',
  roundSummary: undefined,
})

const router = useRouter()
const chatStore = useChatStore()
const sandboxDrawer = useSandboxWorkspaceDrawer()

const emit = defineEmits<{
  toggle: []
  hitlDecided: [token: string, approved: boolean]
}>()

const { timelineStyle } = useTimelineStyle()

function onRowActivate() {
  // exec / websearch / webfetch 只展开详情，不跳转工作区；read 定位文件，其余沙箱步打开工作区
  if (
    isSandboxTool.value
    && !isSandboxExecStep(props.step)
    && !isSandboxFetchStep(props.step)
    && chatStore.currentId
  ) {
    const focus = resolveSandboxFocusPath(props.step)
    // read 步骤：附带起始行，点击后工作区直接定位到对应行（不重复渲染内容）
    const lineRange = isSandboxReadStep(props.step)
      ? resolveSandboxReadLineRange(props.step)
      : undefined
    sandboxDrawer.open({
      conversationId: chatStore.currentId,
      focusPath: focus,
      focusLine: lineRange?.start,
      focusLineEnd: lineRange?.end,
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
  || props.step.summary?.active
  || props.step.id,
)

const cancelCancellableTool = inject<(stepId: string) => void | Promise<void>>(
  'cancelCancellableTool',
  async () => {},
)

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const isPaused = computed(() => lifecycle.value === 'paused' || lifecycle.value === 'terminated')
/** 终态（done/error/skipped/paused/terminated）：label 统一灰，与工具调用完成态一致 */
const isTerminal = computed(() => ['done', 'error', 'skipped', 'paused', 'terminated'].includes(lifecycle.value))

/** 工具步完成后紧跟文案显示 ✓（折叠区内不显示） */
const showCheckmark = computed(() =>
  isTerminal.value && isToolStepId(props.step.id) && props.hideChevron !== true && !props.hideCheckmark,
)

const label = computed(() => {
  // think 步：有结构化摘要显示摘要，无摘要统一兜底「深度思考」（running/done 一致）
  if (isThinkStepId(props.step.id)) {
    if (props.step.stepSummary?.trim()) return props.step.stepSummary.trim()
    return '深度思考'
  }
  // tool 步：去掉「调用工具」前缀，直接显示工具名
  if (isToolStepId(props.step.id)) {
    return formatStepLabel(props.step).replace(/^调用工具\s*/, '').trim() || formatStepLabel(props.step)
  }
  return formatStepLabel(props.step)
})
const canPauseTool = computed(
  () => props.live && isRunning.value && isCancellableSandboxTool(props.step),
)

async function onPauseTool(e: Event): Promise<void> {
  e.stopPropagation()
  e.preventDefault()
  if (!canPauseTool.value) return
  await cancelCancellableTool(props.step.id)
}

/** 主行摘要：折叠时一行预览；展开且可下移时主行仅保留 label */
const headerText = computed(() => {
  // think 步：摘要已由 stepSummary 主行承载，不再展示 before/active/after 冗余文案
  if (isThinkStepId(props.step.id)) return ''
  // exec 步：摘要压缩为命令头（如 `find ... | head -120` → `find | head`），
  // 有 think_summary 摘要时前置展示（如「提交一下改动 git | grep」）；取消/暂停保留状态文案
  if (isSandboxExecStep(props.step)) {
    const cancelled = lifecycle.value === 'paused' || lifecycle.value === 'terminated'
    const command = extractSandboxExecCommand(props.step)
    if (command && !cancelled) {
      // HITL 等待确认：命令在执行前展示（完整命令），便于用户决策
      if (isHitlSummaryAwaiting(props.step)) {
        return `等待确认执行：${command}`
      }
      return formatExecCommandHeaderText(command, props.roundSummary)
    }
  }
  return resolveStepHeaderText(props.step)
})
const shiftSummary = computed(() => shouldShiftSummaryOnExpand(props.step))
const { isSandboxTool, editDiffSummary } = useSandboxToolExpand(() => props.step)

const showHeaderPreview = computed(
  () => !!headerText.value
    && (!props.expanded || !shiftSummary.value),
)

const expandPanels = computed(() => resolveStepExpandPanels(props.step))
const expandSummary = computed(() => expandPanels.value.lead)
const expandBody = computed(() => expandPanels.value.body)

/** intent 步：抽屉「路由过程」trace 列表（方案 B） */
const routingTraces = computed(() => resolveIntentRoutingTraces(props.step))

const canExpand = computed(() => !props.hideChevron && hasExpandableContent(props.step))
const rowClickable = computed(() => canExpand.value || (!props.hideChevron && isSandboxTool.value))

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
  () => [props.live, isRunning.value, props.step.clientStartedAt] as const,
  ([live, running, clientStartedAt]) => {
    clearElapsedTimer()
    if (live && running && typeof clientStartedAt === 'number') {
      const tick = () => {
        liveElapsedMs.value = Math.max(0, Date.now() - clientStartedAt)
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
  if (detailFollowRaf) {
    cancelAnimationFrame(detailFollowRaf)
    detailFollowRaf = 0
  }
})

const durationText = computed(() => {
  if (isDone.value || isPaused.value) {
    const ms = resolveStepDurationMs(props.step)
    if (ms != null) return formatDuration(ms)
  }
  if (isRunning.value && props.live && liveElapsedMs.value != null) {
    return formatDuration(liveElapsedMs.value)
  }
  return ''
})

const showShimmer = computed(() => isRunning.value && !!props.live)

/** 展开区贴底跟随（与正文流式一致）：think 运行中展开自动定位到底部，reasoning 增长持续跟随；
 * 用户上滑离开底部即停跟，滚回底部恢复跟随 */
const detailRef = ref<HTMLElement | null>(null)
const detailPinned = ref(true)
let detailFollowRaf = 0

function onDetailScroll(): void {
  const el = detailRef.value
  if (!el) return
  detailPinned.value = el.scrollHeight - el.scrollTop - el.clientHeight <= 8
}

function followDetailBottom(): void {
  // rAF 合帧：reasoning 逐 token 触发 watch，勿每个 delta 都强排一次滚动
  if (detailFollowRaf) return
  detailFollowRaf = requestAnimationFrame(() => {
    detailFollowRaf = 0
    const el = detailRef.value
    if (!el || !detailPinned.value) return
    el.scrollTop = el.scrollHeight
  })
}

watch(
  () => [props.expanded, isRunning.value, props.live, props.step.reasoning?.length ?? 0] as const,
  ([expanded, running, live]) => {
    // 折叠时复位贴底，下次展开重新从底部跟随
    if (!expanded) {
      detailPinned.value = true
      return
    }
    if (!running || !live || !isThinkStepId(props.step.id)) return
    followDetailBottom()
  },
)
</script>

<template>
  <div
    class="op-line"
    :class="{
      'is-expanded': expanded,
      'is-running': isRunning && live,
      'is-clickable': rowClickable,
      'is-cancellable': canPauseTool,
      'is-expandable': canExpand,
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
      <span class="op-main">
        <span v-if="timelineStyle === 'standard'" class="op-step-icon">
          <TimelineStepIcon class="op-type-icon" :step="step" />
          <svg
            v-if="canExpand"
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
        <span
          class="op-label operation-card-title"
          :class="{ 'op-shimmer': showShimmer, 'is-terminal': isTerminal }"
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
        </span>
        <svg
          v-if="showCheckmark"
          class="op-check"
          width="14"
          height="14"
          viewBox="0 0 16 16"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-label="完成"
        >
          <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" stroke-width="1.5" />
          <polyline points="4.5 8 7 10.5 11.5 5.5" />
        </svg>
        <svg
          v-if="canExpand && timelineStyle !== 'standard'"
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
      <span class="op-trailing">
        <span v-if="durationText" class="op-dur">{{ durationText }}</span>
        <button
          v-if="canPauseTool"
          type="button"
          class="op-pause"
          title="暂停"
          aria-label="暂停"
          @click="onPauseTool"
        >
          <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" width="11" height="11">
            <rect x="3" y="3" width="10" height="10" rx="1.5" />
          </svg>
        </button>
      </span>
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
      @decided="(token, approved) => emit('hitlDecided', token, approved)"
    />

    <div v-if="expanded && canExpand" ref="detailRef" class="op-detail" @scroll.passive="onDetailScroll">
      <!-- intent 步：路由过程（方案 B；普通 meta-line 样式，对齐 RAG「检索过程」先例） -->
      <div v-if="routingTraces.length" class="op-routing-block">
        <p
          v-for="(trace, traceIdx) in routingTraces"
          :key="`${trace.layer ?? 'layer'}-${traceIdx}`"
          class="op-routing-row"
          :title="`${trace.label || trace.layer} ${trace.detail}`"
        >
          <span class="op-routing-key">{{ trace.label || trace.layer }}</span>
          <span class="op-routing-detail">{{ trace.detail }}</span>
        </p>
      </div>
      <SandboxToolExpandPanel
        v-if="isSandboxTool"
        :step="step"
        @open-path="openSandboxPath"
      />
      <template v-else>
        <div v-if="expandSummary && shiftSummary" class="op-detail-after">
          <StaticMarkdown :source="expandSummary" compact :streaming="live" />
        </div>
        <StaticMarkdown v-if="expandBody" :source="expandBody" compact :streaming="live" />
        <div v-if="step.reasoning?.trim()" class="op-detail-thinking">
          <StaticMarkdown :source="step.reasoning" compact :streaming="live" />
        </div>
        <StaticMarkdown v-if="step.output?.trim()" :source="step.output" compact :streaming="live" />
      </template>
    </div>
  </div>
</template>

<style scoped>
.op-line {
  --op-font: var(--sun-font-md);
  --op-font-sm: var(--sun-font-sm);
  --op-detail-font: var(--sun-font-base);
  font-size: var(--op-font);
  line-height: 1.5;
  color: var(--sun-text-muted);
  contain: layout style;
}

.op-line-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
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

.op-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

/* 完成 ✓：紧跟文案，不落在行尾时长旁 */
.op-check {
  flex-shrink: 0;
  align-self: center;
  color: var(--sun-text-muted);
}

.op-shimmer {
  --op-shimmer-base: var(--sun-shimmer-base);
  --op-shimmer-peak: var(--sun-shimmer-peak);
  display: inline-block;
  max-width: 100%;
  background-image: linear-gradient(
    90deg,
    var(--op-shimmer-base) 0%,
    var(--op-shimmer-base) 38%,
    var(--op-shimmer-peak) 50%,
    var(--op-shimmer-base) 62%,
    var(--op-shimmer-base) 100%
  );
  background-size: 220% 100%;
  background-repeat: no-repeat;
  background-position: 100% center;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: op-text-shimmer 1.2s linear infinite;
  will-change: background-position;
}

.op-label.op-shimmer {
  --op-shimmer-base: var(--sun-shimmer-label-base);
  --op-shimmer-peak: var(--sun-shimmer-label-peak);
}

.op-text.op-shimmer {
  opacity: 1;
}

.op-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

/* 终态（done/paused/terminated）label 与工具调用完成态一致，保持灰 */
.op-label.is-terminal {
  color: var(--sun-text-muted);
}

.op-line.is-clickable:hover .op-label.is-terminal {
  color: var(--sun-text-muted);
}

.op-text {
  /* 不撑满：按内容宽度排布，使行尾 chevron 紧跟文字而非被推到最右 */
  flex: 0 1 auto;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.op-trailing {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 1.45em;
  padding-left: 8px;
  padding-top: 0;
}

/* 文字后展开箭头：紧跟 label/text，折叠 > 展开 ^；hover / 展开态显示 */
.op-main .op-chevron {
  flex-shrink: 0;
  align-self: center;
  width: 12px;
  height: 12px;
  color: var(--sun-text-secondary);
  opacity: 0;
  margin-left: 2px;
  transition: transform 0.15s ease, opacity 0.12s ease;
}

.op-line:not(.is-expanded):hover .op-main .op-chevron {
  opacity: 0.85;
}

.op-line.is-expanded .op-main .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}

/* 行首图标槽位：固定 16px，type-icon 与 chevron 绝对定位重叠 */
.op-step-icon {
  position: relative;
  flex-shrink: 0;
  align-self: center;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.op-step-icon .op-type-icon,
.op-step-icon .op-chevron {
  position: absolute;
  transition: opacity 0.12s ease, transform 0.15s ease;
}

.op-step-icon .op-type-icon {
  opacity: 1;
}

.op-step-icon .op-chevron {
  color: var(--sun-text-secondary);
  opacity: 0;
  margin: 0;
}

/* 仅可展开行 hover：图标淡出、> 淡入 */
.op-line.is-expandable:hover .op-step-icon .op-type-icon {
  opacity: 0;
}

.op-line.is-expandable:hover .op-step-icon .op-chevron {
  opacity: 0.85;
}

/* 展开态：类型图标隐藏、^ 常显 */
.op-line.is-expanded .op-step-icon .op-type-icon {
  opacity: 0;
}

.op-line.is-expanded .op-step-icon .op-chevron {
  transform: rotate(90deg);
  opacity: 0.85;
}

.op-dur {
  flex-shrink: 0;
  padding-left: 0;
  padding-top: 1px;
  font-size: var(--op-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  line-height: 1.5;
}

/* Chat 底栏同款圆钮+方块，缩到工具行一行高；仅 hover/focus 显示 */
.op-pause {
  display: none;
  align-items: center;
  justify-content: center;
  width: 1.45em;
  height: 1.45em;
  margin: 0;
  padding: 0;
  border: 1px solid var(--sun-border);
  border-radius: 50%;
  background: transparent;
  color: var(--sun-text-secondary);
  cursor: pointer;
  line-height: 0;
}

.op-line.is-cancellable:hover .op-dur,
.op-line.is-cancellable:focus-within .op-dur {
  display: none;
}

.op-line.is-cancellable:hover .op-pause,
.op-line.is-cancellable:focus-within .op-pause {
  display: inline-flex;
}

.op-pause:hover {
  color: var(--sun-red, #f85149);
  border-color: var(--sun-red, #f85149);
  background: rgba(248, 113, 113, 0.08);
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

/* intent 步抽屉：路由过程区块（普通 meta-line 文本行，无边框色块） */
.op-routing-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.op-routing-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 0;
  min-width: 0;
  font-size: var(--op-detail-font);
  line-height: 1.55;
}

.op-routing-key {
  flex-shrink: 0;
  min-width: 72px;
  color: var(--sun-text-muted);
}

.op-routing-detail {
  min-width: 0;
  color: var(--sun-text-muted);
  word-break: break-all;
}

.op-line.is-running .op-label:not(.op-shimmer) {
  color: var(--sun-text);
}

.op-detail {
  margin: 2px 0 6px 0;
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
  margin-left: 0;
}

@keyframes op-text-shimmer {
  0% { background-position: 100% center; }
  100% { background-position: 0% center; }
}
</style>
