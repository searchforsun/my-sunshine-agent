<script setup lang="ts">
import { computed } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { resolveTimelineStepKind } from '../../api/timelineStepIcon'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  size?: number
}>(), {
  size: 14,
})

const kind = computed(() => resolveTimelineStepKind(props.step))
</script>

<template>
  <svg
    class="timeline-step-icon"
    :class="`is-${kind}`"
    :width="size"
    :height="size"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <!-- decision：问号对话框 -->
    <template v-if="kind === 'decision'">
      <path d="M3 3.5h10a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H8l-2.5 2v-2H3a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M6.2 6.2a1.8 1.8 0 0 1 3.2 1.2c0 1.2-1.4 1.4-1.4 2.3" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
      <circle cx="8" cy="11.2" r="0.55" fill="currentColor" stroke="none" />
    </template>

    <!-- subagent：人形 -->
    <template v-else-if="kind === 'subagent'">
      <circle cx="8" cy="5" r="2.25" stroke="currentColor" stroke-width="1.25" />
      <path d="M3.5 13c0-2.5 2-4 4.5-4s4.5 1.5 4.5 4" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- worker：齿轮 -->
    <template v-else-if="kind === 'worker'">
      <circle cx="8" cy="8" r="2.4" stroke="currentColor" stroke-width="1.25" />
      <path d="M8 2.8v1.6M8 11.6v1.6M2.8 8h1.6M11.6 8h1.6M4.3 4.3l1.1 1.1M10.6 10.6l1.1 1.1M11.7 4.3l-1.1 1.1M5.4 10.6l-1.1 1.1" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- plan：层次列表 -->
    <template v-else-if="kind === 'plan'">
      <path d="M3.5 4h9M3.5 8h6M3.5 12h8" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
      <circle cx="12.5" cy="8" r="1.25" fill="currentColor" stroke="none" />
    </template>

    <!-- rag：放大镜 -->
    <template v-else-if="kind === 'rag'">
      <circle cx="7" cy="7" r="4.25" stroke="currentColor" stroke-width="1.25" />
      <path d="M10.2 10.2L13 13" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- intent：路由岔路 -->
    <template v-else-if="kind === 'intent'">
      <circle cx="8" cy="4.5" r="2" stroke="currentColor" stroke-width="1.25" />
      <path d="M8 6.5v3M8 9.5l-2.8 2.8M8 9.5l2.8 2.8" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- skill：闪电 -->
    <template v-else-if="kind === 'skill'">
      <path d="M8.8 2L4 9h3.2L7 14l5-7H8.6l.2-5z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
    </template>

    <!-- tasks：剪贴板 -->
    <template v-else-if="kind === 'tasks'">
      <path d="M4 2.5h8l1.5 2v9a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 13.5V4.5L4 2.5Z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M5.5 8h5M5.5 10.5h3" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
    </template>

    <!-- think：灯泡 -->
    <template v-else-if="kind === 'think'">
      <path d="M8 2.5a4 4 0 0 0-2.6 7c.6.5.9 1.1.9 1.8h3.4c0-.7.3-1.3.9-1.8a4 4 0 0 0-2.6-7z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M6.6 12.3h2.8M7.1 14h1.8" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" />
    </template>

    <!-- tool-view：文件 -->
    <template v-else-if="kind === 'tool-view'">
      <path d="M3.5 2.5h6L12.5 5.5v8h-9z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M9.5 2.5V5.5h3" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
    </template>

    <!-- tool-edit：铅笔 -->
    <template v-else-if="kind === 'tool-edit'">
      <path d="M11.5 2.5l2 2L5 13H3v-2L11.5 2.5z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M10.5 3.5l2 2" stroke="currentColor" stroke-width="1.1" />
    </template>

    <!-- tool-fetch：网页地球 -->
    <template v-else-if="kind === 'tool-fetch'">
      <circle cx="8" cy="8" r="5" stroke="currentColor" stroke-width="1.25" />
      <path d="M3 8h10M8 3c2 1.5 2.5 3.5 2.5 5S10 11.5 8 13c-2-1.5-2.5-3.5-2.5-5S6 4.5 8 3z" stroke="currentColor" stroke-width="1.1" />
    </template>

    <!-- tool-exec：终端 -->
    <template v-else-if="kind === 'tool-exec'">
      <path d="M2.8 4.5l4 3.5-4 3.5" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" />
      <path d="M8.5 11.5h4.7" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
    </template>

    <!-- tool：开口扳手 -->
    <template v-else-if="kind === 'tool'">
      <circle cx="5" cy="11" r="2.6" stroke="currentColor" stroke-width="1.25" />
      <path d="M7.2 8.8l5.3-5.3a1.9 1.9 0 0 1 2.7 2.7l-5.3 5.3" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round" />
      <path d="M10 6l2 2" stroke="currentColor" stroke-width="1.1" />
    </template>

    <!-- generic：圆点 -->
    <template v-else>
      <circle cx="8" cy="8" r="2.5" stroke="currentColor" stroke-width="1.25" />
    </template>
  </svg>
</template>

<style scoped>
.timeline-step-icon {
  flex-shrink: 0;
  display: block;
  color: var(--sun-text-muted);
}
</style>
