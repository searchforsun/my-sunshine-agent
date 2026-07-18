<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import 'katex/dist/katex.min.css'
import '../utils/stream-markdown/styles.css'
import { renderStaticMarkdown } from '../utils/markdown/renderStaticMarkdown'
import { enhanceStaticMarkdown } from '../utils/stream-markdown/StaticEnhancer'
import {
  hasOpenMermaidFenceAtEnd,
  stripTrailingOpenMermaidFence,
} from '../utils/stream-markdown/mermaidFence'

const props = withDefaults(defineProps<{
  source: string
  /** 抽屉/步骤区等较小字号 */
  compact?: boolean
  /** 展开区最大高度，超出内部滚动 */
  scrollable?: boolean
  /** Skills 文件预览同款样式（msg-md skill-md-preview） */
  skillPreview?: boolean
  /** 流式输出中：未闭合 mermaid 用稳定占位，闭合后立即渲染 */
  deferMermaid?: boolean
}>(), {
  compact: false,
  scrollable: false,
  skillPreview: false,
  deferMermaid: false,
})

const rootRef = ref<HTMLElement | null>(null)

/** 流式且文末 mermaid 未闭合：裁掉该块，改用下方稳定占位，避免 v-html 抖动 */
const openMermaidPending = computed(() =>
  props.deferMermaid && hasOpenMermaidFenceAtEnd(props.source),
)

const renderSource = computed(() =>
  openMermaidPending.value
    ? stripTrailingOpenMermaidFence(props.source)
    : props.source,
)

const html = computed(() => renderStaticMarkdown(renderSource.value))

async function enhanceDom() {
  await nextTick()
  if (rootRef.value) {
    enhanceStaticMarkdown(rootRef.value, {
      deferMermaid: props.deferMermaid,
      source: props.source,
    })
  }
}

watch(html, () => { void enhanceDom() }, { flush: 'post' })
watch(() => props.deferMermaid, (defer, prev) => {
  if (prev && !defer) void enhanceDom()
})
onMounted(() => { void enhanceDom() })
</script>

<template>
  <!-- shell 仅承载流式 mermaid 占位；compact/scroll 必须落在 .msg-md 上，
       否则 OperationCard 等 :deep(.static-md-compact) 灰色样式被 .msg-md { color: sun-text } 盖掉 -->
  <div class="static-md-shell">
    <div
      v-if="html"
      ref="rootRef"
      class="msg-md"
      :class="{
        'static-md': !skillPreview,
        'skill-md-preview': skillPreview,
        'static-md-compact': compact,
        'static-md-scroll': scrollable,
        streaming: deferMermaid,
      }"
      v-html="html"
    />
    <!-- 未闭合围栏：Vue 节点稳定，不随 chunk 重建 -->
    <div v-if="openMermaidPending" class="smd-mermaid-wrapper smd-mermaid-streaming-pending">
      <div class="smd-mermaid-header">
        <span class="smd-mermaid-label">mermaid</span>
      </div>
      <div class="smd-mermaid-loading">
        <div class="smd-loading-spinner" />
        <p>正在生成图表…</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.static-md-shell {
  min-width: 0;
}

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
