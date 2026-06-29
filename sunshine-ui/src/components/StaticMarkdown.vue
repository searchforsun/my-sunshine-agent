<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import 'katex/dist/katex.min.css'
import '../utils/stream-markdown/styles.css'
import { renderStaticMarkdown } from '../utils/markdown/renderStaticMarkdown'
import { enhanceStaticMarkdown } from '../utils/stream-markdown/StaticEnhancer'

const props = withDefaults(defineProps<{
  source: string
  /** 抽屉/步骤区等较小字号 */
  compact?: boolean
  /** 展开区最大高度，超出内部滚动 */
  scrollable?: boolean
}>(), {
  compact: false,
  scrollable: false,
})

const rootRef = ref<HTMLElement | null>(null)

const html = computed(() => renderStaticMarkdown(props.source))

async function enhanceDom() {
  await nextTick()
  if (rootRef.value) enhanceStaticMarkdown(rootRef.value)
}

watch(html, () => { void enhanceDom() }, { flush: 'post' })
onMounted(() => { void enhanceDom() })
</script>

<template>
  <div
    v-if="html"
    ref="rootRef"
    class="msg-md static-md"
    :class="{ 'static-md-compact': compact, 'static-md-scroll': scrollable }"
    v-html="html"
  />
</template>

<style scoped>
.static-md-compact {
  font-size: var(--sun-font-base);
  line-height: 1.55;
}

.static-md-compact :deep(h1),
.static-md-compact :deep(h2),
.static-md-compact :deep(h3) {
  font-size: var(--sun-font-md);
  margin: 10px 0 6px;
}

.static-md-compact :deep(p) {
  margin: 4px 0;
}

.static-md-compact :deep(pre:not(.smd-mermaid-source)) {
  margin: 8px 0;
  padding: 10px 12px;
}

.static-md-scroll {
  max-height: min(40vh, 320px);
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 2px;
}
</style>
