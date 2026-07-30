<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  formatStepLabel,
  resolveStepDurationMs,
  resolveStepHeaderText,
  stepLifecycle,
} from '../../api/processingSteps'
import { fetchPeerRun, parsePeerTranscript, type PeerTranscriptEntry } from '../../api/peerAudit'
import StaticMarkdown from '../StaticMarkdown.vue'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  messageId?: string
  live?: boolean
}>(), {
  live: false,
})

const expanded = ref(false)
const loading = ref(false)
const loadError = ref('')
const transcript = ref<PeerTranscriptEntry[]>([])

const lifecycle = computed(() => stepLifecycle(props.step))
const isRunning = computed(() => lifecycle.value === 'running')
const isDone = computed(() => lifecycle.value === 'done')
const label = computed(() => formatStepLabel(props.step))
const headerText = computed(() => resolveStepHeaderText(props.step))
const showShimmer = computed(() => isRunning.value && props.live)

const canExpand = computed(() => isDone.value && !!props.messageId)

const durationText = computed(() => {
  if (!isDone.value) return ''
  const ms = resolveStepDurationMs(props.step)
  return ms != null ? formatDuration(ms) : ''
})

const groupedTranscript = computed(() => {
  const groups = new Map<number, PeerTranscriptEntry[]>()
  for (const entry of transcript.value) {
    const round = entry.round || 1
    const list = groups.get(round) ?? []
    list.push(entry)
    groups.set(round, list)
  }
  return [...groups.entries()].sort((a, b) => a[0] - b[0])
})

async function loadTranscript(): Promise<void> {
  if (!props.messageId || loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const view = await fetchPeerRun(props.messageId)
    transcript.value = parsePeerTranscript(view?.transcriptJson)
    if (!view) {
      loadError.value = '协作记录尚未就绪'
    } else if (transcript.value.length === 0) {
      loadError.value = '暂无智能体发言记录'
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载协作记录失败'
    transcript.value = []
  } finally {
    loading.value = false
  }
}

function toggleExpand(): void {
  if (!canExpand.value) return
  expanded.value = !expanded.value
  if (expanded.value && transcript.value.length === 0 && !loading.value) {
    void loadTranscript()
  }
}

watch(
  () => [props.messageId, isDone.value] as const,
  ([messageId, done]) => {
    if (!messageId || !done) {
      transcript.value = []
      loadError.value = ''
      expanded.value = false
    }
  },
)
</script>

<template>
  <div
    class="peer-line"
    :class="{
      'is-running': isRunning && live,
      'is-expanded': expanded,
      'is-clickable': canExpand,
    }"
  >
    <div
      class="peer-header"
      :role="canExpand ? 'button' : undefined"
      :tabindex="canExpand ? 0 : -1"
      @click="toggleExpand"
      @keydown.enter.prevent="toggleExpand"
      @keydown.space.prevent="toggleExpand"
    >
      <span class="op-gutter" aria-hidden="true">
        <svg
          v-if="canExpand"
          class="peer-chevron"
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
      <span class="peer-main">
        <span class="peer-label" :class="{ 'op-shimmer': showShimmer }">{{ label }}</span>
        <span v-if="headerText" class="peer-summary" :class="{ 'op-shimmer': showShimmer }">
          {{ headerText }}<span v-if="isRunning && live" class="op-pulse">…</span>
        </span>
      </span>
      <span v-if="durationText" class="peer-dur">{{ durationText }}</span>
    </div>

    <div v-if="expanded && canExpand" class="peer-detail">
      <p v-if="loading" class="peer-status">正在加载协作记录…</p>
      <p v-else-if="loadError" class="peer-status">{{ loadError }}</p>
      <template v-else>
        <section
          v-for="[round, entries] in groupedTranscript"
          :key="round"
          class="peer-round"
        >
          <div class="peer-round-title">第 {{ round }} 轮</div>
          <article
            v-for="(entry, idx) in entries"
            :key="`${round}-${idx}-${entry.roleName}`"
            class="peer-entry"
          >
            <div class="peer-role">{{ entry.roleName }}</div>
            <StaticMarkdown :source="entry.content" compact />
          </article>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.peer-line {
  --op-gutter: 12px;
  --op-font: var(--sun-font-md);
  --op-font-sm: var(--sun-font-sm);
  font-size: var(--op-font);
  line-height: 1.5;
  color: var(--sun-text-muted);
  padding: 1px 0 6px;
}

.peer-header {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr) auto;
  column-gap: 4px;
  align-items: start;
}

.peer-line.is-clickable .peer-header {
  cursor: pointer;
}

.peer-line.is-clickable:hover .peer-label {
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

.peer-chevron {
  flex-shrink: 0;
  color: var(--sun-text-muted);
  opacity: 0.5;
  transition: transform 0.15s ease;
}

.peer-line.is-expanded .peer-chevron {
  transform: rotate(90deg);
}

.peer-main {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.peer-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.peer-line.is-running .peer-label:not(.op-shimmer) {
  color: var(--sun-text);
}

.peer-summary {
  flex: 1 1 0;
  min-width: 0;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.peer-dur {
  flex-shrink: 0;
  padding-left: 10px;
  padding-top: 1px;
  font-size: var(--op-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.peer-detail {
  margin: 4px 0 4px calc(var(--op-gutter) + 4px);
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: min(42vh, 360px);
  overflow-y: auto;
}

.peer-status {
  margin: 0;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.peer-round {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.peer-round-title {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-secondary);
  font-weight: 500;
}

.peer-entry {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 6px;
  border-bottom: 1px solid color-mix(in srgb, var(--sun-border) 65%, transparent);
}

.peer-entry:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.peer-role {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-secondary);
  font-weight: 500;
}

.peer-entry :deep(.static-md-compact) {
  color: var(--sun-text-muted);
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
}

.peer-label.op-shimmer {
  --op-shimmer-base: var(--sun-text);
  --op-shimmer-peak: color-mix(in srgb, var(--sun-text) 22%, white);
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
