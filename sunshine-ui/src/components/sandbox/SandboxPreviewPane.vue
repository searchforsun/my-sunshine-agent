<script setup lang="ts">
import { NIcon } from 'naive-ui'
import {
  CloseOutline,
  CodeSlashOutline,
  DocumentTextOutline,
  EyeOutline,
} from '@vicons/ionicons5'
import CopyToggleIcon from '../icons/CopyToggleIcon.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import { tabFileName } from '../../composables/useSandboxPreviewTabs'

defineProps<{
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
  previewCodeHtml: string
  previewLangClass: string
}>()

const emit = defineEmits<{
  activateTab: [path: string]
  closeTab: [path: string, ev?: Event]
  toggleMdRawMode: []
  copyPreview: []
}>()

const tabbarRef = defineModel<HTMLElement | null>('tabbarRef', { default: null })
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
        :title="tab.path"
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
      class="preview-scroll"
      :class="{ 'preview-scroll--md-wrap': showMarkdownRendered }"
    >
      <div v-if="!selectedPath" class="empty-editor">
        <p class="pane-hint">从左侧打开文件预览</p>
        <p class="pane-sub">只读浏览 · 不可编辑</p>
      </div>
      <p v-else-if="previewLoading" class="pane-hint">读取中…</p>
      <div v-else-if="showMarkdownRendered" class="preview-md">
        <StaticMarkdown :source="preview" />
      </div>
      <pre v-else-if="previewCodeHtml" class="preview-code"><code :class="previewLangClass" v-html="previewCodeHtml" /></pre>
      <pre v-else class="preview-code">{{ preview }}</pre>
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
  overflow: auto;
  padding: 4px 6px 10px;
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
  white-space: pre;
  word-break: normal;
  overflow-wrap: normal;
  width: max-content;
  min-width: 100%;
  tab-size: 4;
}

.preview-code :deep(code),
.preview-code :deep(.hljs),
.preview-code :deep(span) {
  font-family: var(--sun-font-mono, 'JetBrains Mono', ui-monospace, monospace) !important;
  letter-spacing: 0;
  font-variant-ligatures: none;
}

.preview-code code.hljs {
  display: block;
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
