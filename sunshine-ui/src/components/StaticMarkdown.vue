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
  /** 流式输出中：标记容器为 streaming，路径增强暂缓到流式结束，避免重建闪烁 */
  streaming?: boolean
  /** 文件预览场景：当前文件容器路径，用于解析 markdown 内相对链接 */
  basePath?: string
}>(), {
  compact: false,
  scrollable: false,
  skillPreview: false,
  deferMermaid: false,
  streaming: false,
  basePath: '',
})

/** 流式大文本降级阈值：超过该长度不再每次 chunk 跑 markdown-it/hljs 全量解析，
 * 以纯文本 pre-wrap 渲染（只更新文本节点），流式结束切回完整渲染，避免大文件生成卡顿 */
const STREAM_LARGE_SOURCE_LEN = 64 * 1024

const rootRef = ref<HTMLElement | null>(null)

/** 流式且文末 mermaid 未闭合：裁掉该块，改用下方稳定占位，避免 v-html 抖动 */
const openMermaidPending = computed(() =>
  props.deferMermaid && hasOpenMermaidFenceAtEnd(props.source),
)

/** 流式期间超大文本：跳过 markdown 解析与 DOM 重建，纯文本展示 */
const isStreamingLarge = computed(() =>
  (props.streaming || props.deferMermaid) && props.source.length > STREAM_LARGE_SOURCE_LEN,
)

const renderSource = computed(() =>
  openMermaidPending.value
    ? stripTrailingOpenMermaidFence(props.source)
    : props.source,
)

const html = computed(() =>
  isStreamingLarge.value ? '' : renderStaticMarkdown(renderSource.value),
)

const mdClass = computed(() => ({
  'static-md': !props.skillPreview,
  'skill-md-preview': props.skillPreview,
  'static-md-compact': props.compact,
  'static-md-scroll': props.scrollable,
  streaming: props.streaming || props.deferMermaid,
}))

async function enhanceDom() {
  await nextTick()
  if (rootRef.value) {
    enhanceStaticMarkdown(rootRef.value, {
      deferMermaid: props.deferMermaid,
      source: props.source,
      basePath: props.basePath,
    })
  }
}

watch(html, () => { void enhanceDom() }, { flush: 'post' })
watch(() => props.deferMermaid, (defer, prev) => {
  if (prev && !defer) void enhanceDom()
})
watch(() => props.streaming, (now, prev) => {
  // 流式结束：容器移除 streaming 标记后重新增强（路径高亮在流式中已暂缓）
  if (prev && !now) void enhanceDom()
})
onMounted(() => { void enhanceDom() })
</script>

<template>
  <!-- shell 仅承载流式 mermaid 占位；compact/scroll 必须落在 .msg-md 上，
       否则 OperationCard 等 :deep(.static-md-compact) 灰色样式被 .msg-md { color: sun-text } 盖掉 -->
  <div class="static-md-shell">
    <!-- 流式大文本降级：不跑 markdown-it/hljs 全量解析，纯文本 pre-wrap 渲染，避免大文件生成卡顿 -->
    <div
      v-if="isStreamingLarge"
      class="msg-md stream-large-text"
      :class="mdClass"
    >{{ source }}</div>
    <template v-else>
      <div
        v-if="html"
        ref="rootRef"
        class="msg-md"
        :class="mdClass"
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
    </template>
  </div>
</template>

<style scoped>
.static-md-shell {
  min-width: 0;
}

/* 流式大文本降级：保持换行、防超长行撑破；其余继承 .msg-md 排版 */
.stream-large-text {
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: break-word;
  min-height: 1.5em;
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
