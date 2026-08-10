<script setup lang="ts">
import { h, ref, computed, nextTick } from 'vue'
import { NIcon, NInput, NTree, type TreeDragInfo, type TreeOption } from 'naive-ui'
import { ChevronForwardOutline, SearchOutline, CloseOutline, DocumentTextOutline } from '@vicons/ionicons5'

const props = defineProps<{
  treeWidth: number
  canResizeTree: boolean
  treeLoading: boolean
  treeData: TreeOption[]
  expandedKeys: string[]
  selectedKeys: string[]
  onTreeLoad: (option: TreeOption) => Promise<void>
  /** 显示路径转换（工作区模式去掉项目根前缀）；缺省原样展示 */
  displayPath?: (path: string) => string
}>()

const emit = defineEmits<{
  treeResizePointerDown: [ev: PointerEvent]
  dragstart: [info: TreeDragInfo]
  'update:expanded-keys': [keys: Array<string | number>]
  'update:selected-keys': [keys: Array<string | number>, option: Array<TreeOption | null>]
  /** 搜索选中文件 */
  'search-select-file': [path: string]
}>()

const searchMode = ref(false)
const searchQuery = ref('')
const searchInputRef = ref<InstanceType<typeof NInput> | null>(null)

/** 提取路径最后一段作为文件名 */
function extractFileName(fullPath: string): string {
  const parts = fullPath.split('/').filter(Boolean)
  return parts[parts.length - 1] || fullPath
}

/**
 * 从 window.__smd_sandboxIndex 获取全量文件索引。
 * 索引为 Set<string>，由 useSandboxPathIndex 在抽屉打开时自动加载并持续维护。
 */
function getFullFileIndex(): Set<string> {
  const win = window as any
  if (win.__smd_sandboxIndex instanceof Set && win.__smd_sandboxIndex.size > 0) {
    return win.__smd_sandboxIndex as Set<string>
  }
  return new Set()
}

/** 全量扁平文件路径列表（排除目录：目录特征 = 索引中存在以 {path}/ 开头的子路径） */
const allFilePaths = computed<string[]>(() => {
  const idx = getFullFileIndex()
  if (idx.size === 0) return []
  // 将 Set 转为数组，方便做前缀判断
  const paths = [...idx]
  // 构建排序数组用于二分/前缀匹配 — 简单方案：对每个 path 检查是否有以 path/ 开头的子路径
  const result: string[] = []
  for (const p of paths) {
    // 目录：存在其他路径以 p/ 为前缀
    const isDir = paths.some(other => other !== p && other.startsWith(p + '/'))
    if (!isDir) result.push(p)
  }
  return result
})

/** 搜索结果：仅当 query 非空时过滤；默认不展示 */
const filteredFiles = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  // 默认不显示文件，必须输入条件才加载
  if (!q) return [] as { path: string; name: string; displayPath: string }[]
  const result: { path: string; name: string; displayPath: string }[] = []
  const seen = new Set<string>()
  for (const fullPath of allFilePaths.value) {
    const name = extractFileName(fullPath)
    if (!name.toLowerCase().includes(q)) continue
    if (seen.has(fullPath)) continue
    seen.add(fullPath)
    result.push({
      path: fullPath,
      name,
      displayPath: props.displayPath ? props.displayPath(fullPath) : fullPath,
    })
  }
  return result
})

async function openSearch() {
  searchMode.value = true
  searchQuery.value = ''
  await nextTick()
  searchInputRef.value?.focus()
}

function closeSearch() {
  searchMode.value = false
  searchQuery.value = ''
}

function onSearchSelect(path: string) {
  emit('search-select-file', path)
  closeSearch()
}

function denyTreeDrop() {
  return false
}

function treeNodeProps({ option }: { option: TreeOption }) {
  const raw = String((option as TreeOption & { path?: string }).path || option.key)
  return {
    title: props.displayPath ? props.displayPath(raw) : raw,
  }
}

function onLoad(option: TreeOption) {
  return props.onTreeLoad(option)
}
</script>

<template>
  <div class="file-tree-pane" :style="{ width: `${treeWidth}px` }">
    <!-- 普通模式标题栏 -->
    <div v-if="!searchMode" class="tree-section-label">
      <span>资源管理器</span>
      <button type="button" class="icon-btn-sm" title="搜索文件" aria-label="搜索文件" @click="openSearch">
        <NIcon :component="SearchOutline" :size="14" />
      </button>
    </div>

    <!-- 搜索模式 -->
    <template v-if="searchMode">
      <div class="search-bar">
        <div class="search-input-row">
          <NInput
            ref="searchInputRef"
            v-model:value="searchQuery"
            size="tiny"
            placeholder="输入文件名模糊条件"
            clearable
            class="search-input"
            @keydown.esc="closeSearch"
          />
          <button type="button" class="icon-btn-sm" title="关闭搜索" aria-label="关闭搜索" @click="closeSearch">
            <NIcon :component="CloseOutline" :size="14" />
          </button>
        </div>
      </div>
      <div class="search-results-scroll" :class="{ 'is-empty': filteredFiles.length === 0 }">
        <template v-if="filteredFiles.length">
          <div
            v-for="file in filteredFiles"
            :key="file.path"
            class="search-result-item"
            @click="onSearchSelect(file.path)"
          >
            <NIcon :component="DocumentTextOutline" :size="13" class="search-file-icon" />
            <span class="search-file-name">{{ file.name }}</span>
            <span class="search-file-path" :title="file.displayPath">{{ file.displayPath }}</span>
          </div>
        </template>
        <p v-else-if="searchQuery.trim()" class="pane-hint">未找到匹配文件</p>
        <p v-else class="pane-hint">输入文件名关键字搜索</p>
      </div>
    </template>

    <!-- 普通模式文件树 -->
    <div v-if="!searchMode" class="tree-scroll">
      <p v-if="treeLoading" class="pane-hint">加载中…</p>
      <NTree
        v-else-if="treeData.length"
        block-line
        expand-on-click
        :draggable="true"
        :allow-drop="denyTreeDrop"
        :data="treeData"
        :expanded-keys="expandedKeys"
        :selected-keys="selectedKeys"
        :on-load="onLoad"
        :node-props="treeNodeProps"
        :render-switcher-icon="() => h(NIcon, { component: ChevronForwardOutline, size: 12 })"
        @dragstart="emit('dragstart', $event)"
        @update:expanded-keys="emit('update:expanded-keys', $event)"
        @update:selected-keys="(keys, option) => emit('update:selected-keys', keys, option)"
      />
      <p v-else class="pane-hint">暂无文件</p>
    </div>
  </div>
  <div
    v-if="canResizeTree"
    class="tree-resize-handle"
    role="separator"
    aria-orientation="vertical"
    aria-label="调整资源管理器宽度"
    @pointerdown="emit('treeResizePointerDown', $event)"
  />
</template>

<style scoped>
.file-tree-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  flex-shrink: 0;
  border-right: 1px solid var(--sun-border);
}

.tree-section-label {
  flex-shrink: 0;
  padding: 8px 10px 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--sun-text-muted);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.icon-btn-sm {
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  padding: 2px 4px;
  display: inline-flex;
  align-items: center;
  border-radius: 3px;
}

.icon-btn-sm:hover {
  color: var(--sun-text);
  background: color-mix(in srgb, var(--sun-text) 8%, var(--sun-black));
}

.search-bar {
  flex-shrink: 0;
  padding: 6px 8px;
  border-bottom: 1px solid var(--sun-border);
}

.search-input-row {
  display: flex;
  align-items: center;
  gap: 6px;
}


.search-input {
  flex: 1;
  min-width: 0;
}

.search-results-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 0;
  display: flex;
  flex-direction: column;
}

.search-results-scroll.is-empty {
  justify-content: center;
  align-items: center;
}

.search-results-scroll .pane-hint {
  margin: 0;
  padding: 12px 4px;
}

.search-result-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  cursor: pointer;
  font-size: 12px;
  border: 1px solid transparent;
  border-radius: 3px;
  margin: 1px 6px;
  min-width: 0;
}

.search-result-item:hover {
  border-color: var(--sun-border);
  background: color-mix(in srgb, var(--sun-text) 4%, var(--sun-black));
}

.search-file-icon {
  flex-shrink: 0;
  color: var(--sun-text-muted);
}

.search-file-name {
  flex-shrink: 0;
  color: var(--sun-text);
  white-space: nowrap;
}

.search-file-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--sun-text-muted);
  font-size: 11px;
  font-family: var(--sun-font-mono, 'JetBrains Mono', monospace);
}

.search-input :deep(.n-input__input-el) {
  font-size: 12px;
}

.tree-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 6px 10px;
}

.file-tree-pane :deep(.n-tree) {
  --n-node-color-active: transparent !important;
  --n-node-color-hover: transparent !important;
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.file-tree-pane :deep(.n-tree-node-content) {
  padding: 2px 4px;
  border: 1px solid transparent;
  border-radius: 3px;
  display: flex;
  align-items: center;
  min-width: 0;
  cursor: grab;
}

.file-tree-pane :deep(.n-tree-node-content:active) {
  cursor: grabbing;
}

.file-tree-pane :deep(.n-tree-node-content__text) {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-tree-pane :deep(.n-tree-node-content:hover) {
  border-color: var(--sun-border);
}

.file-tree-pane :deep(.n-tree-node--selected > .n-tree-node-content) {
  border-color: var(--sun-border);
  color: var(--sun-text);
  font-weight: 600;
  background: transparent !important;
}

.file-tree-pane :deep(.tree-icon-dir),
.file-tree-pane :deep(.tree-icon-file) {
  color: var(--sun-text-muted);
  margin-right: 2px;
}

.file-tree-pane :deep(.tree-size) {
  flex-shrink: 0;
  margin-left: 8px;
  font-size: 10px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, 'JetBrains Mono', monospace);
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
}

.tree-resize-handle {
  flex-shrink: 0;
  width: 6px;
  margin-left: -3px;
  cursor: col-resize;
  z-index: 2;
  position: relative;
}

.tree-resize-handle::after {
  content: '';
  position: absolute;
  left: 2px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: transparent;
}

.tree-resize-handle:hover::after,
:global(body.sandbox-tree-resizing) .tree-resize-handle::after {
  background: var(--sun-border);
}

:global(body.sandbox-tree-resizing) {
  cursor: col-resize !important;
  user-select: none;
}

.pane-hint {
  margin: 8px 4px;
  font-size: 12px;
  color: var(--sun-text-muted);
}
</style>
