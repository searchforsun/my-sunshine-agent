<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  type: string
  size?: number
}>(), {
  size: 14,
})

/** 归一化 planner / timeline 可能出现的 type 别名 */
const kind = computed(() => {
  const t = props.type?.trim().toLowerCase() || 'node'
  if (t === 'plan') return 'plan'
  return t
})
</script>

<template>
  <svg
    class="plan-node-icon"
    :class="`is-${kind}`"
    :width="size"
    :height="size"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <!-- start：入口 -->
    <template v-if="kind === 'start'">
      <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.25" />
      <path d="M7 5.5v5l4-2.5-4-2.5z" fill="currentColor" stroke="none" />
    </template>

    <!-- rag：检索 -->
    <template v-else-if="kind === 'rag'">
      <circle cx="7" cy="7" r="4.25" stroke="currentColor" stroke-width="1.25" />
      <path d="M10.2 10.2L13 13" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" />
      <path d="M4.5 12h7" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" opacity="0.45" />
      <path d="M5.5 10h5" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" opacity="0.35" />
    </template>

    <!-- tool：工具调用 -->
    <template v-else-if="kind === 'tool'">
      <ellipse cx="8" cy="4.75" rx="5" ry="1.75" stroke="currentColor" stroke-width="1.25" />
      <path
        d="M3 4.75v3.5c0 1.2 2.24 2.25 5 2.25s5-1.05 5-2.25v-3.5"
        stroke="currentColor"
        stroke-width="1.25"
        stroke-linejoin="round"
      />
      <path
        d="M3 8.25v3.5c0 1.2 2.24 2.25 5 2.25s5-1.05 5-2.25v-3.5"
        stroke="currentColor"
        stroke-width="1.25"
        stroke-linejoin="round"
      />
    </template>

    <!-- agent：子 Agent -->
    <template v-else-if="kind === 'agent'">
      <path
        d="M8 2.75L12.5 5.5V10.5L8 13.25L3.5 10.5V5.5L8 2.75Z"
        stroke="currentColor"
        stroke-width="1.25"
        stroke-linejoin="round"
      />
      <circle cx="8" cy="8" r="1.35" fill="currentColor" stroke="none" />
    </template>

    <!-- llm：综合分析 -->
    <template v-else-if="kind === 'llm'">
      <path
        d="M8 2v2.5M8 11.5V14M2 8h2.5M11.5 8H14M4.1 4.1l1.75 1.75M10.15 10.15l1.75 1.75M11.9 4.1l-1.75 1.75M6.85 10.15l-1.75 1.75"
        stroke="currentColor"
        stroke-width="1.2"
        stroke-linecap="round"
      />
      <circle cx="8" cy="8" r="1.5" fill="currentColor" stroke="none" />
    </template>

    <!-- answer：汇总输出 -->
    <template v-else-if="kind === 'answer'">
      <path
        d="M3.5 4.25h9a1.75 1.75 0 0 1 1.75 1.75v3.5A1.75 1.75 0 0 1 12.5 11h-2.35L8 13v-2H3.5A1.75 1.75 0 0 1 1.75 9.25v-3.5A1.75 1.75 0 0 1 3.5 4.25z"
        stroke="currentColor"
        stroke-width="1.25"
        stroke-linejoin="round"
      />
      <path
        d="M5.25 8l1.35 1.35L10.1 5.85"
        stroke="currentColor"
        stroke-width="1.2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </template>

    <!-- plan -->
    <template v-else-if="kind === 'plan'">
      <path
        d="M3.5 4h9M3.5 8h6M3.5 12h8"
        stroke="currentColor"
        stroke-width="1.25"
        stroke-linecap="round"
      />
      <circle cx="12.5" cy="8" r="1.25" fill="currentColor" stroke="none" />
    </template>

    <!-- fallback -->
    <template v-else>
      <circle cx="8" cy="8" r="5.25" stroke="currentColor" stroke-width="1.25" />
      <circle cx="8" cy="8" r="1.25" fill="currentColor" stroke="none" opacity="0.55" />
    </template>
  </svg>
</template>

<style scoped>
.plan-node-icon {
  flex-shrink: 0;
  display: block;
  color: var(--sun-text-secondary);
}

.is-start,
.is-answer {
  color: var(--sun-text-muted);
}

.is-rag {
  color: color-mix(in srgb, var(--sun-text-secondary) 88%, var(--sun-blue, #58a6ff));
}

.is-tool {
  color: color-mix(in srgb, var(--sun-text-secondary) 90%, var(--sun-text));
}

.is-agent {
  color: color-mix(in srgb, var(--sun-text-secondary) 85%, var(--sun-gold, #dbb84a));
}

.is-llm {
  color: color-mix(in srgb, var(--sun-text-secondary) 82%, var(--sun-text));
}
</style>
