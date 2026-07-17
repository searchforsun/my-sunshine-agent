<script setup lang="ts">
import { computed, h, nextTick, ref, watch } from 'vue'
import { NIcon, NTree, type TreeDragInfo, type TreeOption } from 'naive-ui'
import {
  CheckmarkOutline,
  ChevronForwardOutline,
  CloseOutline,
  CodeSlashOutline,
  CopyOutline,
  DocumentTextOutline,
  EyeOutline,
  FolderOpenOutline,
  FolderOutline,
  RefreshOutline,
} from '@vicons/ionicons5'
import {
  listSandboxWorkspace,
  readSandboxWorkspaceFile,
  type SandboxFsNode,
} from '../../api/sandboxWorkspace'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'
import { useWriteHitlMode } from '../../composables/useWriteHitlMode'
import { setSandboxPathDrag } from '../../utils/sandboxPathChip'
import WriteHitlModeSelector from './WriteHitlModeSelector.vue'
import { registerHljsLanguages } from '../../utils/markdown/registerHljsLanguages'
import { copyText } from '../../utils/stream-markdown/clipboard'
import { formatFileSize } from '../../utils/buildFileTree'
import StaticMarkdown from '../StaticMarkdown.vue'
import markdown from 'highlight.js/lib/languages/markdown'

const hljs = registerHljsLanguages()
if (!hljs.getLanguage('markdown')) {
  hljs.registerLanguage('markdown', markdown)
}

const {
  state,
  close,
  drawerWidth,
  canResizeDrawer,
  treeWidth,
  canResizeTree,
  onResizePointerDown,
  onTreeResizePointerDown,
} = useSandboxWorkspaceDrawer()

const { mode: writeHitlMode } = useWriteHitlMode(() => state.conversationId)

const treeLoading = ref(false)
const errorText = ref('')
const treeData = ref<TreeOption[]>([])
const expandedKeys = ref<string[]>([])
const selectedKeys = ref<string[]>([])
const selectedPath = ref('')
const openTabs = ref<{ path: string }[]>([])
const tabbarRef = ref<HTMLElement | null>(null)
/** .md 默认美化；true = 原始源码（支持横向滚动） */
const mdRawMode = ref(false)

function scrollActiveTabIntoView() {
  void nextTick(() => {
    const active = tabbarRef.value?.querySelector('.editor-tab.active') as HTMLElement | null
    active?.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' })
  })
}

watch(selectedPath, (path) => {
  mdRawMode.value = false
  if (path) scrollActiveTabIntoView()
})
const preview = ref('')
const previewMeta = ref('')
const previewLoading = ref(false)
/** path → 已加载正文（切换标签免重复请求） */
const previewCache = ref<Record<string, { content: string; meta: string }>>({})

function tabFileName(path: string): string {
  const parts = path.split('/').filter(Boolean)
  return parts[parts.length - 1] || path
}

const fileName = computed(() => tabFileName(selectedPath.value))

const isMarkdownFile = computed(() => selectedPath.value.toLowerCase().endsWith('.md'))
const showMarkdownRendered = computed(() => isMarkdownFile.value && !mdRawMode.value)

function langFromPath(path: string): string | null {
  const dot = path.lastIndexOf('.')
  if (dot < 0) return null
  const ext = path.slice(dot).toLowerCase()
  const map: Record<string, string> = {
    '.py': 'python',
    '.sh': 'bash',
    '.bash': 'bash',
    '.json': 'json',
    '.yaml': 'yaml',
    '.yml': 'yaml',
    '.sql': 'sql',
    '.xml': 'xml',
    '.html': 'xml',
    '.htm': 'xml',
    '.js': 'javascript',
    '.ts': 'typescript',
    '.jsx': 'javascript',
    '.tsx': 'typescript',
    '.java': 'java',
    '.rs': 'rust',
    '.cpp': 'cpp',
    '.c': 'c',
    '.md': 'markdown',
  }
  return map[ext] ?? null
}

const previewCodeHtml = computed(() => {
  if (!preview.value || !selectedPath.value || showMarkdownRendered.value) return ''
  const lang = langFromPath(selectedPath.value)
  try {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(preview.value, { language: lang }).value
    }
    return hljs.highlightAuto(preview.value).value
  } catch {
    return ''
  }
})

const previewLangClass = computed(() => {
  const lang = langFromPath(selectedPath.value) || 'plaintext'
  return `hljs language-${lang}`
})

const canCopyPreview = computed(() => !!preview.value && !previewLoading.value)
const copyDone = ref(false)
let copyTimer: ReturnType<typeof setTimeout> | null = null

async function copyPreview() {
  if (!preview.value) return
  const ok = await copyText(preview.value)
  if (!ok) return
  copyDone.value = true
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = setTimeout(() => {
    copyDone.value = false
    copyTimer = null
  }, 2000)
}

const breadcrumbs = computed(() => {
  if (!selectedPath.value) return [] as { label: string; path: string }[]
  const parts = selectedPath.value.split('/').filter(Boolean)
  const out: { label: string; path: string }[] = []
  let acc = ''
  for (const p of parts) {
    acc += `/${p}`
    out.push({ label: p, path: acc })
  }
  return out
})

function dirIcon(expanded: boolean) {
  return () => h(NIcon, {
    component: expanded ? FolderOpenOutline : FolderOutline,
    size: 14,
    class: 'tree-icon-dir',
  })
}

function fileIcon() {
  return () => h(NIcon, {
    component: DocumentTextOutline,
    size: 14,
    class: 'tree-icon-file',
  })
}

function toOptions(entries: SandboxFsNode[]): TreeOption[] {
  const sorted = [...entries].sort((a, b) => {
    const ad = a.type === 'dir' ? 0 : 1
    const bd = b.type === 'dir' ? 0 : 1
    if (ad !== bd) return ad - bd
    return a.name.localeCompare(b.name)
  })
  return sorted.map((n) => {
    const isDir = n.type === 'dir'
    const size = typeof n.size === 'number' && n.size >= 0 ? n.size : null
    const opt: TreeOption = {
      key: n.path,
      label: n.name,
      isLeaf: !isDir,
      prefix: isDir ? dirIcon(false) : fileIcon(),
      suffix: !isDir && size != null
        ? () => h('span', { class: 'tree-size' }, formatFileSize(size))
        : undefined,
    }
    ;(opt as TreeOption & { path: string; isDir: boolean }).path = n.path
    ;(opt as TreeOption & { path: string; isDir: boolean }).isDir = isDir
    return opt
  })
}

async function fetchChildren(path: string): Promise<TreeOption[]> {
  if (!state.conversationId) return []
  const data = await listSandboxWorkspace(state.conversationId, path)
  return toOptions(data.entries ?? [])
}

async function loadRoots() {
  if (!state.conversationId) return
  treeLoading.value = true
  errorText.value = ''
  try {
    const [wsKids, skillKids] = await Promise.all([
      fetchChildren('/workspace').catch(() => [] as TreeOption[]),
      fetchChildren('/skills').catch(() => [] as TreeOption[]),
    ])
    treeData.value = [
      {
        key: '/workspace',
        label: 'workspace',
        isLeaf: false,
        path: '/workspace',
        isDir: true,
        children: wsKids,
        prefix: dirIcon(true),
      },
      {
        key: '/skills',
        label: 'skills',
        isLeaf: false,
        path: '/skills',
        isDir: true,
        children: skillKids,
        prefix: dirIcon(true),
      },
    ]
    expandedKeys.value = ['/workspace', '/skills']
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '加载失败'
    treeData.value = []
  } finally {
    treeLoading.value = false
  }
}

function treeNodeProps({ option }: { option: TreeOption }) {
  return {
    title: String((option as TreeOption & { path?: string }).path || option.key),
  }
}

/** NTree 须树级 draggable；node-props 里的 draggable/onDragstart 会被组件覆盖 */
function onTreeDragStart({ event, node }: TreeDragInfo) {
  const ext = node as TreeOption & { path?: string; isDir?: boolean }
  const path = (ext.path || String(node.key)).trim()
  if (!path.startsWith('/workspace') && !path.startsWith('/skills')) return
  if (!event.dataTransfer) return
  setSandboxPathDrag(event.dataTransfer, {
    path,
    name: String(node.label ?? path),
    isDir: !!ext.isDir || node.isLeaf === false,
  })
}

function denyTreeDrop() {
  return false
}

async function onLoad(option: TreeOption): Promise<void> {
  const path = String(option.key)
  try {
    option.children = await fetchChildren(path)
  } catch {
    option.children = []
  }
}

function onUpdateExpanded(keys: Array<string | number>) {
  expandedKeys.value = keys.map(String)
}

async function openFile(path: string) {
  if (!state.conversationId || !path || path === '/workspace' || path === '/skills') return
  if (!openTabs.value.some((t) => t.path === path)) {
    openTabs.value = [...openTabs.value, { path }]
  }
  selectedPath.value = path
  selectedKeys.value = [path]
  const cached = previewCache.value[path]
  if (cached) {
    preview.value = cached.content
    previewMeta.value = cached.meta
    previewLoading.value = false
    return
  }
  previewLoading.value = true
  preview.value = ''
  previewMeta.value = ''
  try {
    const data = await readSandboxWorkspaceFile(state.conversationId, path)
    let content = ''
    let meta = ''
    if (data.binary) {
      meta = '二进制文件，暂不支持预览'
    } else {
      content = data.content ?? ''
      meta = data.truncated ? '内容已截断' : ''
    }
    previewCache.value = { ...previewCache.value, [path]: { content, meta } }
    if (selectedPath.value === path) {
      preview.value = content
      previewMeta.value = meta
    }
  } catch (e) {
    const meta = e instanceof Error ? e.message : '读取失败'
    previewCache.value = { ...previewCache.value, [path]: { content: '', meta } }
    if (selectedPath.value === path) {
      preview.value = ''
      previewMeta.value = meta
    }
  } finally {
    if (selectedPath.value === path) {
      previewLoading.value = false
    }
  }
}

function activateTab(path: string) {
  if (selectedPath.value === path) return
  selectedPath.value = path
  selectedKeys.value = [path]
  const cached = previewCache.value[path]
  if (cached) {
    preview.value = cached.content
    previewMeta.value = cached.meta
    previewLoading.value = false
  } else {
    void openFile(path)
  }
}

function closeTab(path: string, ev?: Event) {
  ev?.stopPropagation()
  const idx = openTabs.value.findIndex((t) => t.path === path)
  if (idx < 0) return
  const next = openTabs.value.filter((t) => t.path !== path)
  openTabs.value = next
  const { [path]: _removed, ...rest } = previewCache.value
  previewCache.value = rest
  if (selectedPath.value !== path) return
  if (next.length === 0) {
    selectedPath.value = ''
    selectedKeys.value = []
    preview.value = ''
    previewMeta.value = ''
    previewLoading.value = false
    return
  }
  const fallback = next[Math.min(idx, next.length - 1)]
  activateTab(fallback.path)
}

function onSelect(keys: Array<string | number>, option: Array<TreeOption | null>) {
  selectedKeys.value = keys.map(String)
  const opt = option[0]
  if (!opt) return
  const path = String(opt.key)
  const isDir = (opt as TreeOption & { isDir?: boolean }).isDir || !opt.isLeaf
  if (isDir) {
    const set = new Set(expandedKeys.value)
    if (set.has(path)) set.delete(path)
    else set.add(path)
    expandedKeys.value = [...set]
    return
  }
  void openFile(path)
}

async function refresh() {
  const keepTabs = openTabs.value.map((t) => t.path)
  const keepActive = selectedPath.value
  previewCache.value = {}
  await loadRoots()
  if (keepTabs.length) {
    openTabs.value = keepTabs.map((path) => ({ path }))
    if (keepActive) await openFile(keepActive)
  }
}

async function revealPath(focus: string) {
  if (!focus.startsWith('/workspace') && !focus.startsWith('/skills')) return
  const parts = focus.split('/').filter(Boolean)
  // expand ancestors: /workspace/a/b.txt → /workspace, /workspace/a
  const ancestors: string[] = []
  let acc = ''
  for (let i = 0; i < parts.length - 1; i++) {
    acc += `/${parts[i]}`
    ancestors.push(acc)
  }
  // ensure children loaded along path
  for (const dir of ancestors) {
    await ensureExpanded(dir)
  }
  expandedKeys.value = [...new Set([...expandedKeys.value, ...ancestors])]
  const isLikelyFile = parts.length > 1 && (focus.includes('.') || !focus.endsWith('/'))
  if (isLikelyFile && focus !== '/workspace' && focus !== '/skills') {
    await openFile(focus)
  }
}

async function ensureExpanded(dirPath: string) {
  const find = (nodes: TreeOption[], key: string): TreeOption | null => {
    for (const n of nodes) {
      if (String(n.key) === key) return n
      if (n.children?.length) {
        const hit = find(n.children as TreeOption[], key)
        if (hit) return hit
      }
    }
    return null
  }
  let node = find(treeData.value, dirPath)
  if (!node) return
  if (!node.children || node.children.length === 0) {
    await onLoad(node)
    // trigger reactivity
    treeData.value = [...treeData.value]
  }
}

watch(
  () => [state.open, state.conversationId, state.focusPath] as const,
  ([open, convId, focus], prev) => {
    if (!open || !convId) {
      treeData.value = []
      expandedKeys.value = []
      selectedKeys.value = []
      selectedPath.value = ''
      openTabs.value = []
      previewCache.value = {}
      preview.value = ''
      previewMeta.value = ''
      errorText.value = ''
      return
    }
    const prevConv = prev?.[1]
    if (prevConv && prevConv !== convId) {
      openTabs.value = []
      previewCache.value = {}
      selectedPath.value = ''
      selectedKeys.value = []
      preview.value = ''
      previewMeta.value = ''
    }
    void (async () => {
      await loadRoots()
      if (focus) await revealPath(focus)
    })()
  },
)
</script>

<template>
  <aside
    v-if="state.open"
    class="sandbox-drawer"
    role="complementary"
    aria-label="沙箱工作区"
    :style="{ width: `${drawerWidth}px` }"
  >
    <div
      v-if="canResizeDrawer"
      class="drawer-resize-handle"
      role="separator"
      aria-orientation="vertical"
      aria-label="调整抽屉宽度"
      @pointerdown="onResizePointerDown"
    />
    <header class="drawer-header">
      <div class="drawer-head-top">
        <h3 class="drawer-title">沙箱工作区</h3>
        <span class="readonly-badge">只读</span>
        <div class="drawer-head-actions">
          <WriteHitlModeSelector v-model="writeHitlMode" />
          <button type="button" class="icon-btn" title="刷新" aria-label="刷新" @click="refresh">
            <NIcon :component="RefreshOutline" :size="15" />
          </button>
          <button type="button" class="drawer-close" aria-label="关闭" @click="close">×</button>
        </div>
      </div>
    </header>

    <div class="explorer">
      <div class="file-tree-pane" :style="{ width: `${treeWidth}px` }">
        <div class="tree-section-label">资源管理器</div>
        <div class="tree-scroll">
          <p v-if="treeLoading" class="pane-hint">加载中…</p>
          <p v-else-if="errorText" class="pane-error">{{ errorText }}</p>
          <NTree
            v-else
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
            @dragstart="onTreeDragStart"
            @update:expanded-keys="onUpdateExpanded"
            @update:selected-keys="onSelect"
          />
          <p v-if="!treeLoading && !errorText && !treeData.length" class="pane-hint">暂无文件</p>
        </div>
      </div>
      <div
        v-if="canResizeTree"
        class="tree-resize-handle"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整资源管理器宽度"
        @pointerdown="onTreeResizePointerDown"
      />

      <div class="file-preview-pane">
        <div v-if="openTabs.length" ref="tabbarRef" class="editor-tabbar">
          <button
            v-for="tab in openTabs"
            :key="tab.path"
            type="button"
            class="editor-tab"
            :class="{ active: tab.path === selectedPath }"
            :title="tab.path"
            @click="activateTab(tab.path)"
          >
            <NIcon :component="DocumentTextOutline" :size="13" />
            <span class="tab-name">{{ tabFileName(tab.path) }}</span>
            <span
              class="tab-close"
              title="关闭"
              role="button"
              tabindex="0"
              @click="closeTab(tab.path, $event)"
              @keydown.enter.prevent="closeTab(tab.path, $event)"
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
              @click="mdRawMode = !mdRawMode"
            >
              <NIcon :component="mdRawMode ? EyeOutline : CodeSlashOutline" :size="14" />
            </button>
            <button
              v-if="canCopyPreview"
              type="button"
              class="preview-copy-btn smd-toolbtn"
              :title="copyDone ? '已复制' : '复制'"
              @click="copyPreview"
            >
              <NIcon :component="copyDone ? CheckmarkOutline : CopyOutline" :size="14" />
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
    </div>
  </aside>
</template>

<style scoped>
.sandbox-drawer {
  position: relative;
  flex-shrink: 0;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--sun-border);
  background: var(--sun-black);
  color: var(--sun-text);
}

.drawer-resize-handle {
  position: absolute;
  left: -3px;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 2;
}

.drawer-resize-handle::after {
  content: '';
  position: absolute;
  left: 2px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: transparent;
}

.drawer-resize-handle:hover::after,
:global(body.sandbox-drawer-resizing) .drawer-resize-handle::after {
  background: var(--sun-border);
}

.drawer-header {
  flex-shrink: 0;
  padding: 10px 12px;
  border-bottom: 1px solid var(--sun-border);
}

.drawer-head-top {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.drawer-title {
  margin: 0;
  font-size: var(--sun-font-base);
  font-weight: 550;
  color: var(--sun-text);
}

.readonly-badge {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--sun-text-muted);
  border: 1px solid var(--sun-border);
  padding: 1px 6px;
  border-radius: 3px;
}

.drawer-head-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.icon-btn {
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  padding: 2px 4px;
  display: inline-flex;
  align-items: center;
}

.icon-btn:hover {
  color: var(--sun-text);
}

.drawer-close {
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 2px 6px;
}

.drawer-close:hover {
  color: var(--sun-text);
}

.explorer {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  position: relative;
}

.file-tree-pane,
.file-preview-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.file-tree-pane {
  flex-shrink: 0;
  border-right: 1px solid var(--sun-border);
}

.file-preview-pane {
  flex: 1 1 auto;
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

.tree-section-label {
  flex-shrink: 0;
  padding: 8px 10px 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--sun-text-muted);
}

.tree-scroll,
.preview-scroll {
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

.pane-hint,
.pane-error {
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

.pane-error {
  color: var(--sun-danger, #e07070);
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
