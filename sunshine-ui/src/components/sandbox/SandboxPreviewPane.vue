<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { NIcon } from 'naive-ui'
import {
  AddOutline,
  CloseOutline,
  CodeSlashOutline,
  DocumentTextOutline,
  EyeOutline,
} from '@vicons/ionicons5'
import CopyToggleIcon from '../icons/CopyToggleIcon.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import CodeLineGutter from './CodeLineGutter.vue'
import { tabFileName } from '../../composables/useSandboxPreviewTabs'

const props = defineProps<{
  openTabs: { path: string }[]
  selectedPath: string
  preview: string
  previewMeta: string
  previewLoading: boolean
  breadcrumbs: { label: string; path: string }[]
  isMarkdownFile: boolean
  mdRawMode: boolean
  canCopyPreview: boolean
  copyDone: boolean
  showMarkdownRendered: boolean
  previewLangClass: string
  previewCodeHtml: string[]
  focusLine?: number
  /** 定位行范围终点（正文 `path` L1-20 点击）；缺省仅 focusLine 单行 */
  focusLineEnd?: number
  /** 显示路径转换（工作区模式去掉项目根前缀）；缺省原样展示 */
  displayPath?: (path: string) => string
}>()

const emit = defineEmits<{
  activateTab: [path: string]
  closeTab: [path: string, ev?: Event]
  toggleMdRawMode: []
  copyPreview: []
  addSelection: [payload: { start: number; end: number }]
}>()

const tabbarRef = defineModel<HTMLElement | null>('tabbarRef', { default: null })
const scrollEl = ref<HTMLElement | null>(null)
const lineEls = ref<Record<string, HTMLElement | null>>({})

/** 当前选中的代码行范围（预览区鼠标选中） */
const selectionRange = ref<{ start: number; end: number } | null>(null)
const selectionBtnTop = ref(0)

const previewLines = computed(() => {
  if (props.showMarkdownRendered || !props.preview) return [] as string[]
  const normalized = props.preview.endsWith('\n')
    ? props.preview.slice(0, -1)
    : props.preview
  if (normalized === '') return ['']
  return normalized.split('\n')
})

function setLineEl(line: number, el: unknown) {
  lineEls.value[String(line)] = el instanceof HTMLElement ? el : null
}

function lineFromNode(node: Node): number | null {
  const el = node.nodeType === Node.ELEMENT_NODE ? (node as HTMLElement) : node.parentElement
  const lineEl = el?.closest?.('.preview-line') as HTMLElement | null
  if (!lineEl) return null
  const n = Number(lineEl.dataset.line)
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

function onPreviewMouseUp(e: MouseEvent) {
  if (props.showMarkdownRendered) return
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

// 切换文件 / 内容变化时清除选中
watch(
  () => [props.selectedPath, props.preview, props.showMarkdownRendered] as const,
  () => {
    selectionRange.value = null
  },
)

/** 程序化原生选区：用 Range/Selection 模拟鼠标拖选 L 范围（原生选中底色 + 「添加到会话」按钮） */
function applyNativeLineSelection(start: number, end: number) {
  const first = lineEls.value[String(start)]
  const last = lineEls.value[String(end)]
  if (!first || !last) return
  // 只选中行内容（code 元素），不含行号槽，视觉与鼠标拖选一致
  const firstCode = first.querySelector('code') ?? first
  const lastCode = last.querySelector('code') ?? last
  const range = document.createRange()
  range.setStartBefore(firstCode)
  range.setEndAfter(lastCode)
  const sel = window.getSelection()
  if (!sel) return
  sel.removeAllRanges()
  sel.addRange(range)
  selectionRange.value = { start, end }
  selectionBtnTop.value = Math.max(first.offsetTop - 28, 0)
}

watch(
  () => [props.selectedPath, props.focusLine, props.focusLineEnd, props.previewLoading, props.preview] as const,
  () => {
    if (props.showMarkdownRendered) return
    void nextTick(() => {
      const start = props.focusLine
      if (!start || start < 1) return
      const end = props.focusLineEnd && props.focusLineEnd >= start ? props.focusLineEnd : start
      const lineEl = lineEls.value[String(start)]
      const scroller = scrollEl.value
      if (lineEl && scroller) {
        const top = lineEl.offsetTop - Math.min(scroller.clientHeight / 2, 160)
        scroller.scrollTo({ top: Math.max(top, 0) })
      }
      applyNativeLineSelection(start, end)
    })
  },
)
</script>

<template>
  <div class="file-preview-pane">
    <div v-if="openTabs.length" ref="tabbarRef" class="editor-tabbar">
      <button
        v-for="tab in openTabs"
        :key="tab.path"
        type="button"
        class="editor-tab"
        :class="{ active: tab.path === selectedPath }"
        :title="displayPath ? displayPath(tab.path) : tab.path"
        @click="emit('activateTab', tab.path)"
      >
        <NIcon :component="DocumentTextOutline" :size="13" />
        <span class="tab-name">{{ tabFileName(tab.path) }}</span>
        <span
          class="tab-close"
          title="关闭"
          role="button"
          tabindex="0"
          @click="emit('closeTab', tab.path, $event)"
          @keydown.enter.prevent="emit('closeTab', tab.path, $event)"
        >
          <NIcon :component="CloseOutline" :size="12" />
        </span>
      </button>
    </div>
    <div v-if="selectedPath" class="editor-breadcrumb">
      <div class="breadcrumb-path">
        <template v-for="(crumb, i) in breadcrumbs" :key="crumb.path">
          <span v-if="i > 0" class="crumb-sep">/</span>
          <span class="crumb">{{ crumb.label }}</span>
        </template>
        <span v-if="previewMeta" class="preview-meta">{{ previewMeta }}</span>
      </div>
      <div class="preview-toolbar">
        <button
          v-if="isMarkdownFile && canCopyPreview"
          type="button"
          class="preview-copy-btn smd-toolbtn"
          :title="mdRawMode ? '美化显示' : '原始显示'"
          :aria-label="mdRawMode ? '美化显示' : '原始显示'"
          @click="emit('toggleMdRawMode')"
        >
          <NIcon :component="mdRawMode ? EyeOutline : CodeSlashOutline" :size="14" />
        </button>
        <button
          v-if="canCopyPreview"
          type="button"
          class="preview-copy-btn smd-toolbtn"
          :title="copyDone ? '已复制' : '复制'"
          @click="emit('copyPreview')"
        >
              <CopyToggleIcon :copied="copyDone" />
        </button>
      </div>
    </div>
    <div
      ref="scrollEl"
      class="preview-scroll"
      :class="{ 'preview-scroll--md-wrap': showMarkdownRendered }"
      @mouseup="onPreviewMouseUp"
    >
      <div v-if="!selectedPath" class="empty-editor">
        <p class="pane-hint">从左侧打开文件预览</p>
        <p class="pane-sub">只读浏览 · 不可编辑</p>
      </div>
      <p v-else-if="previewLoading" class="pane-hint">读取中…</p>
      <div v-else-if="showMarkdownRendered" class="preview-md">
        <StaticMarkdown :source="preview" :base-path="selectedPath" />
      </div>
      <pre v-else-if="previewLines.length" class="preview-code preview-code--guttered">
        <div
          v-for="(line, i) in previewLines"
          :key="i"
          :ref="(el) => setLineEl(i + 1, el)"
          :data-line="i + 1"
          class="preview-line"
          :class="{
            'preview-line--focus': focusLine === i + 1,
            'is-selected': !!selectionRange && i + 1 >= selectionRange.start && i + 1 <= selectionRange.end,
          }"
        >
          <CodeLineGutter mode="file" :new-line="i + 1" />
          <code :class="previewLangClass" v-html="previewCodeHtml[i]" />
        </div>
      </pre>
      <pre v-else class="preview-code">{{ preview }}</pre>
      <button
        v-if="selectionRange && !showMarkdownRendered"
        type="button"
        class="add-selection-btn"
        :style="{ top: `${selectionBtnTop}px` }"
        title="添加到会话"
        @mousedown.prevent.stop
        @click.stop="addSelectionToChat"
      >
        <NIcon :component="AddOutline" :size="13" />
        <span>添加到会话</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.file-preview-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  flex: 1 1 auto;
}

.editor-tabbar {
  flex-shrink: 0;
  display: flex;
  align-items: stretch;
  border-bottom: 1px solid var(--sun-border);
  min-height: 32px;
  background: transparent;
  overflow-x: auto;
  overflow-y: hidden;
}

.editor-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 6px 0 12px;
  font-size: 12px;
  color: var(--sun-text-muted);
  border: none;
  border-right: 1px solid var(--sun-border);
  border-bottom: 1px solid transparent;
  max-width: 180px;
  background: transparent;
  cursor: pointer;
  font: inherit;
  flex-shrink: 0;
}

.editor-tab:hover {
  color: var(--sun-text-secondary);
}

.editor-tab.active {
  color: var(--sun-text);
  border-bottom-color: var(--sun-black);
  margin-bottom: -1px;
}

.tab-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
}

.tab-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  color: var(--sun-text-muted);
  opacity: 0.55;
  flex-shrink: 0;
}

.editor-tab:hover .tab-close,
.editor-tab.active .tab-close {
  opacity: 0.85;
}

.tab-close:hover {
  color: var(--sun-text);
  opacity: 1;
  background: color-mix(in srgb, var(--sun-text-muted) 16%, transparent);
}

.editor-breadcrumb {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px 2px 10px;
  border-bottom: 1px solid var(--sun-border);
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
}

.breadcrumb-path {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
}

.crumb-sep {
  opacity: 0.5;
  margin: 0 1px;
}

.crumb {
  color: var(--sun-text-secondary);
}

.preview-meta {
  margin-left: 8px;
  font-family: inherit;
  font-size: 10px;
  color: var(--sun-text-muted);
}

.preview-toolbar {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.preview-copy-btn {
  flex-shrink: 0;
}

.preview-scroll {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 4px 6px 10px;
  position: relative;
}

.preview-scroll--md-wrap {
  overflow-x: hidden;
}

.empty-editor {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 4px;
  padding: 24px;
}

.pane-hint {
  margin: 8px 4px;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.pane-sub {
  margin: 0;
  font-size: 11px;
  color: var(--sun-text-muted);
  opacity: 0.8;
}

.preview-code {
  margin: 0;
  padding: 12px;
  border: none;
  background: transparent;
  color: var(--sun-text);
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 400;
  line-height: 1.55;
  letter-spacing: 0;
  font-variant-ligatures: none;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
  width: 100%;
  min-width: 0;
  tab-size: 4;
}

.preview-code--guttered {
  display: block;
  padding: 8px 12px 12px 0;
}

.preview-line {
  display: flex;
  align-items: flex-start;
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
  /* 跳过不可见区域的渲染，大幅提升大文件滚动性能 */
  content-visibility: auto;
  contain-intrinsic-size: auto 19px;
}

.preview-line--focus {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 14%, transparent);
  box-shadow: inset 2px 0 0 var(--sun-blue, #58a6ff);
}

.preview-line.is-selected {
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

.preview-line code {
  flex: 1;
  min-width: 0;
  padding: 0 0 0 4px;
  margin: 0;
  background: transparent !important;
  white-space: pre-wrap;
  overflow-wrap: break-word;
  word-break: normal;
}

.preview-code :deep(code),
.preview-code :deep(.hljs),
.preview-code :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
  letter-spacing: 0;
  font-variant-ligatures: none;
}

.preview-code code.hljs {
  display: inline;
  padding: 0;
  background: transparent !important;
  color: inherit;
  font-size: inherit;
  font-weight: inherit;
  line-height: inherit;
  white-space: inherit;
  word-break: inherit;
}

.preview-md {
  padding: 4px 8px 12px;
  font-size: 13px;
  color: var(--sun-text);
  overflow-x: hidden;
  word-break: break-word;
  overflow-wrap: anywhere;
}
</style>
