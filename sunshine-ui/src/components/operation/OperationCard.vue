<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { formatDuration, resolveStepDurationMs, formatStepLabel } from '../../api/processingSteps'

const props = defineProps<{
  step: ProcessingStep
  expanded: boolean
  live?: boolean
}>()

const emit = defineEmits<{
  toggle: []
}>()

const lifecycle = computed(() => props.step.lifecycle ?? props.step.status ?? 'pending')
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const label = computed(() => formatStepLabel(props.step))

/** 主行摘要：running 用 active，done 用 after */
const headerText = computed(() => {
  if (isRunning.value && props.step.summary?.active) {
    return props.step.summary.active
  }
  if (isDone.value && props.step.summary?.after) {
    return props.step.summary.after
  }
  if (props.step.detail?.trim()) return props.step.detail
  if (props.step.result?.trim()) return props.step.result
  if (props.step.summary?.before) return props.step.summary.before
  return ''
})

/** 详情区：展示 before / 中间态 / 流式 reasoning·output，不省略 */
const bodyLines = computed(() => {
  const lines: string[] = []
  const before = props.step.summary?.before?.trim()
  const active = props.step.summary?.active?.trim()
  const after = props.step.summary?.after?.trim()

  if (before) lines.push(before)
  if (active && active !== headerText.value && (!after || active !== after)) {
    lines.push(active)
  }
  if (after && after !== headerText.value && !isRunning.value) {
    lines.push(after)
  }
  return lines
})

const hasCollapsibleBody = computed(() => {
  return bodyLines.value.length > 0
    || !!props.step.reasoning?.trim()
    || !!props.step.output?.trim()
})

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

onUnmounted(clearElapsedTimer)

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
      'is-clickable': hasCollapsibleBody,
    }"
  >
    <div
      class="op-line-row"
      :role="hasCollapsibleBody ? 'button' : undefined"
      :tabindex="hasCollapsibleBody ? 0 : -1"
      @click="hasCollapsibleBody && emit('toggle')"
      @keydown.enter.prevent="hasCollapsibleBody && emit('toggle')"
      @keydown.space.prevent="hasCollapsibleBody && emit('toggle')"
    >
      <span class="op-gutter" aria-hidden="true">
        <svg
          v-if="hasCollapsibleBody"
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
        <span v-if="headerText" class="op-text" :class="{ 'op-shimmer': showShimmer }">
          {{ headerText }}<span v-if="isRunning && live" class="op-pulse">…</span>
        </span>
      </span>
      <span v-if="durationText" class="op-dur">{{ durationText }}</span>
    </div>

    <div v-if="expanded && hasCollapsibleBody" class="op-detail">
      <div
        v-for="(line, i) in bodyLines"
        :key="`${step.id}-body-${i}`"
        class="op-detail-line"
      >
        {{ line }}
      </div>
      <div v-if="step.reasoning?.trim()" class="op-detail-thinking">
        <pre class="op-detail-pre">{{ step.reasoning }}</pre>
      </div>
      <pre v-if="step.output?.trim()" class="op-detail-pre">{{ step.output }}</pre>
    </div>
  </div>
</template>

<style scoped>
.op-line {
  --op-gutter: 12px;
  --op-detail-inset: calc(var(--op-gutter) + 4px);
  --op-font: 13px;
  --op-font-sm: 12px;
  font-size: var(--op-font);
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.op-line-row {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr) auto;
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
  flex-wrap: wrap;
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
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: normal;
  word-break: break-word;
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
}

.op-detail-line {
  font-size: var(--op-font-sm);
  color: var(--sun-text-muted);
  line-height: 1.5;
  opacity: 0.9;
  white-space: normal;
  word-break: break-word;
}

.op-detail-thinking {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.op-detail-tag {
  opacity: 0.75;
  font-weight: 450;
}

.op-detail-pre {
  margin: 0;
  padding: 0;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: var(--op-font-sm);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text-muted);
  opacity: 0.88;
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
