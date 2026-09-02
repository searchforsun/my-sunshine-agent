<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NIcon } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import type { SandboxDiffLine } from '../../api/sandboxEditDiff'
import { registerHljsLanguages } from '../../utils/markdown/registerHljsLanguages'
import CodeLineGutter from './CodeLineGutter.vue'

const props = defineProps<{
  lines: SandboxDiffLine[]
  lang: string | null
  /** 允许鼠标选中 diff 行范围并“添加到输入框”（add/ctx 行映射新文件行号） */
  selectable?: boolean
}>()

const emit = defineEmits<{
  addSelection: [payload: { start: number; end: number }]
}>()

const hljs = registerHljsLanguages()

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 语法高亮缓存（模块级）：大 diff 只高亮已加载行，重新展开 / 静默刷新直接复用，
    避免每次打开都重新 highlight（highlight.js 是最大性能瓶颈） */
const highlightCache = new Map<string, string>()

/** 逐行语法高亮并缓存；失败回退纯文本转义，保证非空 */
function highlightCode(text: string, lang: string | null): string {
  if (!text) return ''
  const key = `${lang ?? ''}\u0000${text}`
  const hit = highlightCache.get(key)
  if (hit !== undefined) return hit
  let html = ''
  try {
    if (lang && hljs.getLanguage(lang)) {
      html = hljs.highlight(text, { language: lang }).value
    } else {
      html = hljs.highlightAuto(text).value
    }
  } catch {
    html = ''
  }
  if (!html) html = escapeHtml(text)
  highlightCache.set(key, html)
  return html
}

type RenderLine = {
  kind: SandboxDiffLine['kind']
  oldLine: number | null | undefined
  newLine: number | null | undefined
  mark: '' | '+' | '-'
  html: string
  isFold: boolean
}

/** 单行组装：fold/hunk 轻量处理，add/del/ctx 走高亮缓存 */
function buildRenderLine(line: SandboxDiffLine): RenderLine {
  if (line.kind === 'fold') {
    return { kind: line.kind, oldLine: line.oldLine, newLine: line.newLine, mark: '', html: '', isFold: true }
  }
  if (line.kind === 'hunk') {
    return { kind: line.kind, oldLine: null, newLine: null, mark: '', html: escapeHtml(line.text || ''), isFold: false }
  }
  const mark = (line.kind === 'del' ? '-' : line.kind === 'add' ? '+' : '') as '' | '+' | '-'
  return {
    kind: line.kind,
    oldLine: line.oldLine,
    newLine: line.newLine,
    mark,
    html: highlightCode(line.text || ' ', props.lang),
    isFold: false,
  }
}

/** 变更行惰性渲染：打开即固定渲染一大块（约 100 行，覆盖两屏以上），
    滚动到尾部哨兵才再追加下一固定块，避免频繁分批导致的滚动顿挫；
    只对已加载行做语法高亮，高亮结果走模块级缓存 */
const PAGE_SIZE = 100
const visibleCount = ref(PAGE_SIZE)

/** 当前应渲染的行：仅对可见切片逐行组装（高亮走缓存），不触碰未加载行 */
const visibleLines = computed<RenderLine[]>(() => props.lines.slice(0, visibleCount.value).map(buildRenderLine))

const sentinelEl = ref<HTMLElement | null>(null)

/** 找到哨兵最近的、可滚动的祖先容器；无则返回 null（此时以视口为判据） */
function nearestScrollableParent(el: HTMLElement): HTMLElement | null {
  let cur = el.parentElement
  while (cur) {
    const oy = getComputedStyle(cur).overflowY
    if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay') && cur.scrollHeight > cur.clientHeight) {
      return cur
    }
    cur = cur.parentElement
  }
  return null
}

/** 哨兵当前是否位于滚动容器的可视区（预加载提前量 300px） */
function isSentinelReachable(): boolean {
  const el = sentinelEl.value
  if (!el) return true
  const r = el.getBoundingClientRect()
  const scroller = nearestScrollableParent(el)
  if (!scroller) {
    return r.top < window.innerHeight && r.bottom > 0
  }
  const sr = scroller.getBoundingClientRect()
  return r.top < sr.bottom + 300 && r.bottom > sr.top
}

function loadMoreRows() {
  const total = props.lines.length
  if (visibleCount.value >= total) return
  visibleCount.value = Math.min(total, visibleCount.value + PAGE_SIZE)
}

/** 无论滚动来自自身还是外层容器，capture 统一捕获，按哨兵可视性追加 */
const onScrollCapture = () => {
  if (isSentinelReachable()) loadMoreRows()
}

onMounted(() => {
  window.addEventListener('scroll', onScrollCapture, true)
  loadMoreRows()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScrollCapture, true)
})

const scrollEl = ref<HTMLElement | null>(null)
const lineEls = ref<Record<string, HTMLElement | null>>({})
const selectionRange = ref<{ start: number; end: number } | null>(null)
const selectionBtnTop = ref(0)

function setLineEl(line: number, el: unknown) {
  lineEls.value[String(line)] = el instanceof HTMLElement ? el : null
}

/** 选中区域落在哪些新文件行上（add/ctx 行有 newLine；del 不可选） */
function lineFromNode(node: Node): number | null {
  const el = node.nodeType === Node.ELEMENT_NODE ? (node as HTMLElement) : node.parentElement
  const rowEl = el?.closest?.('.diff-row') as HTMLElement | null
  if (!rowEl) return null
  const raw = rowEl.dataset.newline
  const n = Number(raw)
  return Number.isFinite(n) && n > 0 ? n : null
}

function computeSelectionRange(): { start: number; end: number } | null {
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null
  const range = sel.getRangeAt(0)
  const scroller = scrollEl.value
  if (!scroller || !scroller.contains(range.startContainer) || !scroller.contains(range.endContainer)) {
    return null
  }
  const startLine = lineFromNode(range.startContainer)
  const endLine = lineFromNode(range.endContainer)
  if (!startLine || !endLine) return null
  return {
    start: Math.min(startLine, endLine),
    end: Math.max(startLine, endLine),
  }
}

function onDiffMouseUp(e: MouseEvent) {
  if (!props.selectable) return
  const t = e.target as HTMLElement | null
  if (t?.closest?.('.add-selection-btn')) return
  void nextTick(() => {
    const r = computeSelectionRange()
    if (r) {
      selectionRange.value = r
      const el = lineEls.value[String(r.start)]
      selectionBtnTop.value = el ? Math.max(el.offsetTop - 28, 0) : 0
    } else {
      selectionRange.value = null
    }
  })
}

function addSelectionToChat() {
  const r = selectionRange.value
  if (!r) return
  selectionRange.value = null
  emit('addSelection', r)
}

watch(
  () => props.lines,
  () => {
    visibleCount.value = PAGE_SIZE
    selectionRange.value = null
    // lines 变化后重新按固定批渲染首屏
    loadMoreRows()
  },
)

watch(
  () => props.selectable,
  () => {
    selectionRange.value = null
  },
)
</script>

<template>
  <div ref="scrollEl" class="sandbox-diff-view" @mouseup="onDiffMouseUp">
    <div
      v-for="(line, idx) in visibleLines"
      :key="idx"
      class="diff-row"
      :class="[
        `is-${line.kind}`,
        { 'is-fold': line.isFold },
        { 'is-selected': !!selectionRange && !!line.newLine && line.newLine >= selectionRange.start && line.newLine <= selectionRange.end },
      ]"
      :data-newline="line.newLine ?? ''"
      :ref="(el) => setLineEl(line.newLine ?? -1, el)"
    >
      <CodeLineGutter
        mode="diff"
        :old-line="line.oldLine"
        :new-line="line.newLine"
        :mark="line.mark"
      />
      <code v-if="line.isFold" class="diff-fold">···</code>
      <code v-else-if="line.kind === 'hunk'" class="diff-hunk" v-html="line.html" />
      <code v-else class="diff-code hljs" v-html="line.html" />
    </div>
    <!-- 惰性渲染哨兵：进入视口（向下滑动接近底部）时加载下一批变更行 -->
    <div v-show="visibleCount < props.lines.length" ref="sentinelEl" class="diff-load-sentinel" />
    <button
      v-if="selectable && selectionRange"
      type="button"
      class="add-selection-btn"
      :style="{ top: `${selectionBtnTop}px` }"
      title="添加到会话"
      @mousedown.prevent.stop
      @click.stop="addSelectionToChat"
    >
      <NIcon :component="AddOutline" :size="13" />
      <span>添加到输入框</span>
    </button>
  </div>
</template>

<style scoped>
.sandbox-diff-view {
  position: relative;
  display: flex;
  flex-direction: column;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  letter-spacing: 0;
  font-variant-ligatures: none;
  tab-size: 4;
  color: var(--sun-text-muted);
}

/* 惰性渲染哨兵：占位触发滚动追加逻辑，无可见外观 */
.diff-load-sentinel {
  height: 1px;
  flex-shrink: 0;
}

.diff-row {
  display: flex;
  align-items: flex-start;
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
}

.diff-row.is-selected {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 10%, transparent);
}

.add-selection-btn {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  z-index: 6;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  padding: 0 11px;
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  color: var(--sun-text-secondary);
  background: var(--sun-black);
  border: 1px solid var(--sun-border-light);
  border-radius: 6px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.35);
  cursor: pointer;
  white-space: nowrap;
  user-select: none;
  transition: color 0.12s, border-color 0.12s;
}

.add-selection-btn:hover {
  color: var(--sun-blue, #58a6ff);
  border-color: var(--sun-blue, #58a6ff);
}

.diff-row.is-del {
  background: color-mix(in srgb, #c44 28%, transparent);
}

.diff-row.is-add {
  background: color-mix(in srgb, #2a9a5c 28%, transparent);
}

.diff-row.is-fold {
  background: transparent;
}

.diff-row.is-hunk {
  background: transparent;
}

.diff-hunk {
  flex: 1;
  min-width: 0;
  padding: 2px 4px;
  margin: 0;
  background: transparent !important;
  color: color-mix(in srgb, var(--sun-text-muted) 62%, transparent);
  white-space: pre-wrap;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}

.diff-code,
.diff-fold {
  flex: 1;
  min-width: 0;
  padding: 0 4px;
  margin: 0;
  background: transparent !important;
  white-space: pre-wrap;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}

.diff-fold {
  color: var(--sun-text-muted);
  opacity: 0.65;
  user-select: none;
}

.diff-row :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
}
</style>
