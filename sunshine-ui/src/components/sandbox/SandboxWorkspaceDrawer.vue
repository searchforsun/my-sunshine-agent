<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { NIcon, NButton } from 'naive-ui'
import { RefreshOutline, WarningOutline, GitCompareOutline, DocumentTextOutline } from '@vicons/ionicons5'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'
import { sandboxWorkspaceRefresh, sandboxPathIndexRefresh } from '../../composables/sandboxWorkspaceRefresh'
import {
  workspaceBanner,
  setWorkspaceBanner,
  clearWorkspaceBanner,
} from '../../composables/sandboxWorkspaceBanner'
import { useWriteHitlMode } from '../../composables/useWriteHitlMode'
import { useSandboxFileTree } from '../../composables/useSandboxFileTree'
import { useSandboxPreviewTabs, stripWorkspaceRootPath } from '../../composables/useSandboxPreviewTabs'
import { syncWorkspace } from '../../api/workspaces'
import WriteHitlModeSelector from './WriteHitlModeSelector.vue'
import DrawerCollapseIcon from '../icons/DrawerCollapseIcon.vue'
import SandboxFileTreePane from './SandboxFileTreePane.vue'
import SandboxPreviewPane from './SandboxPreviewPane.vue'
import SandboxDiffPanel from './SandboxDiffPanel.vue'

const props = defineProps<{
  workspaceId?: string | null
  /** 工作区模式下选中的 checkoutId（缺省 main），文件树根直接映射项目目录 */
  checkoutId?: string | null
  /** 工作区模式根节点显示名（项目名） */
  workspaceName?: string | null
}>()

const {
  state,
  close,
  switchTab,
  openFileFromDiff,
  drawerWidth,
  canResizeDrawer,
  treeWidth,
  canResizeTree,
  onResizePointerDown,
  onTreeResizePointerDown,
} = useSandboxWorkspaceDrawer()

const diffPanelRef = ref<{ refresh: () => void } | null>(null)

const { mode: writeHitlMode } = useWriteHitlMode(() => state.conversationId)


let openFile: (path: string, focusLine?: number) => void | Promise<void> = () => {}
const {
  treeLoading,
  errorText,
  timedOut,
  treeData,
  expandedKeys,
  selectedKeys,
  loadRoots,
  onLoad,
  onTreeDragStart,
  onUpdateExpanded,
  onSelect,
  revealPath,
  resetTree,
  reloadBranch,
} = useSandboxFileTree({
  getConversationId: () => state.conversationId,
  getWorkspaceId: () => props.workspaceId ?? null,
  getCheckoutId: () => props.checkoutId || null,
  getWorkspaceName: () => props.workspaceName || null,
  onOpenFile: (path, focusLine) => openFile(path, focusLine),
})

// 文件树刷新版本号：refresh/checkout切换/sync 完成后 +1，通知会话级路径索引重新加载
const treeVersion = sandboxPathIndexRefresh

const {
  selectedPath,
  openTabs,
  tabbarRef,
  mdRawMode,
  preview,
  previewMeta,
  previewLoading,
  copyDone,
  isMarkdownFile,
  showMarkdownRendered,
  previewLangClass,
  previewCodeHtml,
  canCopyPreview,
  breadcrumbs,
  focusLine,
  copyPreview,
  openFile: previewOpenFile,
  activateTab,
  closeTab,
  resetPreview,
  resetTabsOnConversationChange,
  clearCache,
  clearCacheUnder,
} = useSandboxPreviewTabs({
  getConversationId: () => state.conversationId,
  getWorkspaceId: () => props.workspaceId ?? null,
  getWorkspaceRootPath: () => (props.checkoutId ? `/workspace/${props.checkoutId}` : null),
  selectedKeys,
})
openFile = previewOpenFile

/** 显示路径转换：工作区模式去掉 /workspace/{checkoutId} 前缀 */
function displayPath(path: string): string {
  return stripWorkspaceRootPath(path, props.checkoutId ? `/workspace/${props.checkoutId}` : null)
}

async function refresh() {
  if (diffMode.value) {
    diffPanelRef.value?.refresh()
    return
  }
  // 重建文件树（不主动触发 git 操作）
  const keepTabs = openTabs.value.map((t) => t.path)
  const keepActive = selectedPath.value
  clearCache()
  await loadRoots()
  treeVersion.value++
  if (keepTabs.length) {
    openTabs.value = keepTabs.map((path) => ({ path }))
    if (keepActive) await previewOpenFile(keepActive)
  }
}

/** 工作区代码未正确克隆时的重试：调用 sync API（远端拉取或重新 clone） */
const retrying = ref(false)
const retryError = ref('')

async function retryClone() {
  if (!props.workspaceId) {
    retryError.value = '未选择工作区'
    return
  }
  retrying.value = true
  retryError.value = ''
  try {
    await syncWorkspace(props.workspaceId)
    retryError.value = ''
    // 工作区级别文件树（无需等待会话创建）
    try { await loadRoots(); treeVersion.value++ } catch { /* ok */ }
  } catch (e) {
    const msg = (e as any)?.message || String(e)
    retryError.value = msg || '同步失败，请检查 Git 配置和仓库地址'
  } finally {
    retrying.value = false
  }
}

/** 工作区变化时重置状态 */
watch(() => props.workspaceId, () => {
  retryError.value = ''
  clearWorkspaceBanner()
})

/** checkout 切换（新建 worktree / 选中其他 checkout）时重载文件树，根指向新项目目录 */
watch(() => props.checkoutId, () => {
  clearWorkspaceBanner('tree')
  clearWorkspaceBanner('diff')
  clearWorkspaceBanner('diff-summary')
  if (!state.open) return
  clearCache()
  void loadRoots()
})

/** 是否任务工作区（带 workspaceId）；chat 沙箱不显示 git 相关提示 */
const isTaskWorkspace = computed(() => !!props.workspaceId)

/** 当前处于「改动」diff 视图（消息卡片点击 / 头部 tab 进入） */
const diffMode = computed(() => state.tab === 'diff')

/** 改动视图展示条件：v-show 保持组件挂载实现懒加载（切换 tab 不销毁、不重复加载） */
const showDiffView = computed(() =>
  diffMode.value && isTaskWorkspace.value && !!props.workspaceId && !!props.checkoutId,
)

/** 改动视图点击文件名 -> 跳转文件区定位该文件（git 相对路径需拼 checkout 根） */
function openFileFromDiffTab(path: string) {
  const cid = props.checkoutId
  if (!cid || !path) return
  const full = path.startsWith('/') ? path : `/workspace/${cid}/${path}`
  openFileFromDiff(full)
}

/** 文件树根是否有子节点（工作区模式根 = 项目 checkout 目录；无子节点可能表示 clone 失败） */
const workspaceEmpty = computed(() => {
  // 工作区模式：根 = /workspace/{checkoutId}，只有项目目录一个根
  const projectRoot = treeData.value.find(t => String(t.key).startsWith('/workspace/') && t.key !== '/skills')
  if (projectRoot && (!projectRoot.children || projectRoot.children.length === 0)) return true
  const root = treeData.value.find(t => t.key === '/workspace')
  if (root && (!root.children || root.children.length === 0)) return true
  // 加载失败且 workspace 根不存在，也可能是 clone 未完成
  if (errorText.value && !treeLoading.value &&
      !treeData.value.some(t => t.key === '/workspace' || (String(t.key).startsWith('/workspace/') && t.key !== '/skills'))) {
    return true
  }
  return false
})

/** 尚未选择 checkout（新任务未发送）：右侧工作区无代码属正常态，不视为失败 */
const noCheckoutYet = computed(() => !props.checkoutId)

/** 同步/空工作区 → 统一横幅（有具体文件树错误时让出，避免盖住更具体文案） */
watch(
  [isTaskWorkspace, noCheckoutYet, workspaceEmpty, treeLoading, retryError, errorText],
  () => {
    if (!isTaskWorkspace.value || noCheckoutYet.value) {
      clearWorkspaceBanner('sync')
      return
    }
    if (retryError.value) {
      setWorkspaceBanner('sync', { text: retryError.value, kind: 'error', retryable: true })
      return
    }
    if (workspaceEmpty.value && !treeLoading.value && !errorText.value) {
      setWorkspaceBanner('sync', { text: '工作区加载失败，请重试', kind: 'error', retryable: true })
      return
    }
    clearWorkspaceBanner('sync')
  },
)

/** 文件树加载失败 → 统一横幅（面板内不再重复展示） */
watch(errorText, (text) => {
  if (text) setWorkspaceBanner('tree', { text, kind: 'error' })
  else clearWorkspaceBanner('tree')
})

async function refreshBranch(scope: 'workspace' | 'skills') {
  const prefix = scope === 'workspace' ? '/workspace' : '/skills'
  clearCacheUnder(prefix)
  await reloadBranch(prefix)
  treeVersion.value++
  const keepActive = selectedPath.value
  if (keepActive?.startsWith(prefix)) {
    await previewOpenFile(keepActive)
  }
}

function toggleMdRawMode() {
  mdRawMode.value = !mdRawMode.value
}

/** 预览区选中行 -> 添加到会话（由 ChatView 注入全局回调，插入输入框引用） */
function onAddSelection(payload: { start: number; end: number }) {
  const path = selectedPath.value
  const cb = (window as any).__smd_addSandboxSelection as
    | ((path: string, start: number, end: number) => void)
    | undefined
  if (!path || !cb) return
  cb(path, payload.start, payload.end)
}

watch(
  () => [state.open, state.conversationId, state.focusPath, state.focusLine] as const,
  ([open, convId, focus, focusLine], prev) => {
    if (!open || !convId) {
      resetTree()
      resetPreview()
      return
    }
    const prevOpen = prev?.[0]
    const prevConv = prev?.[1]
    if (prevConv && prevConv !== convId) {
      resetTabsOnConversationChange()
    }
    // 每次打开（或会话切换）都强制重新加载文件树：
    // docker stop 后容器需重启，旧缓存可能指向失效会话/失效路径
    if (prevOpen === undefined || !prevOpen || (prevConv && prevConv !== convId)) {
      resetTree()
    }
    void (async () => {
      await loadRoots()
      if (focus) await revealPath(focus, focusLine || undefined)
    })()
  },
)

/** 加载超时自动返回：关闭抽屉（容器可能未就绪，避免一直转圈） */
watch(timedOut, (v) => {
  if (v) close()
})

watch(
  () => sandboxWorkspaceRefresh.tick,
  () => {
    if (!state.open || !state.conversationId) return
    if (sandboxWorkspaceRefresh.conversationId !== state.conversationId) return
    // 工具终态等信号：文件树与 diff 摘要均按需刷新（diff 面板仅已加载过才重拉，未进入过保持懒加载）
    diffPanelRef.value?.refresh()
    void refreshBranch(sandboxWorkspaceRefresh.scope)
  },
)
</script>

<template>
  <aside
    v-if="state.open"
    class="sandbox-drawer"
    role="complementary"
    aria-label="工作区"
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
        <!-- 任务工作区：标题换图标 + 文件/改动 tab 切换；chat 沙箱保持原样 -->
        <template v-if="isTaskWorkspace">
          <div class="drawer-tabs" role="tablist" aria-label="工作区视图">
            <button
              type="button"
              class="drawer-tab"
              :class="{ active: state.tab === 'files' }"
              role="tab"
              :aria-selected="state.tab === 'files'"
              title="文件浏览"
              @click="switchTab('files')"
            >
              <NIcon :component="DocumentTextOutline" :size="13" class="drawer-tab-icon" />
              <span class="drawer-tab-label">文件</span>
            </button>
            <button
              type="button"
              class="drawer-tab"
              :class="{ active: state.tab === 'diff' }"
              role="tab"
              :aria-selected="state.tab === 'diff'"
              :disabled="!props.checkoutId"
              :title="props.checkoutId ? '查看改动' : '请先发送消息以拉取代码'"
              @click="switchTab('diff')"
            >
              <NIcon :component="GitCompareOutline" :size="13" class="drawer-tab-icon" />
              <span class="drawer-tab-label">改动</span>
            </button>
          </div>
        </template>
        <template v-else>
          <h3 class="drawer-title">工作区</h3>
        </template>
        <div class="drawer-head-actions">
          <slot name="head-actions-pre" />
          <WriteHitlModeSelector v-model="writeHitlMode" />
          <button type="button" class="icon-btn" title="刷新" aria-label="刷新" @click="refresh">
            <NIcon :component="RefreshOutline" :size="15" />
          </button>
          <button type="button" class="drawer-close" title="收起" aria-label="收起" @click="close">
            <DrawerCollapseIcon :size="16" />
          </button>
        </div>
      </div>
    </header>
    <!-- 工作区统一错误/提示行（git / 同步 / 文件树 / 改动） -->
    <div
      v-if="workspaceBanner"
      class="ws-banner"
      :class="`ws-banner--${workspaceBanner.kind}`"
      role="status"
    >
      <NIcon v-if="workspaceBanner.kind === 'error'" :component="WarningOutline" :size="13" />
      <span class="ws-banner-text">{{ workspaceBanner.text }}</span>
      <NButton
        v-if="workspaceBanner.retryable"
        size="tiny"
        type="primary"
        :loading="retrying"
        @click="retryClone"
      >重试</NButton>
    </div>
    <!-- 未选择分支（新任务未发送）：提示选择分支并发送以拉取代码 -->
    <div v-if="isTaskWorkspace && noCheckoutYet" class="ws-no-checkout-hint">
      选择分支并发送消息后拉取代码到工作区
    </div>

    <!-- 改动 diff 视图（仅任务工作区；v-show 常驻挂载实现懒加载，切换 tab 不重新拉取） -->
    <SandboxDiffPanel
      v-show="showDiffView"
      ref="diffPanelRef"
      :workspace-id="props.workspaceId!"
      :checkout-id="props.checkoutId!"
      :initial-path="state.diffPath || undefined"
      @open-file="openFileFromDiffTab"
    />

    <div v-show="!showDiffView" class="explorer">
      <SandboxFileTreePane
        :tree-width="treeWidth"
        :can-resize-tree="canResizeTree"
        :tree-loading="treeLoading"
        :tree-data="treeData"
        :expanded-keys="expandedKeys"
        :selected-keys="selectedKeys"
        :on-tree-load="onLoad"
        :display-path="displayPath"
        @tree-resize-pointer-down="onTreeResizePointerDown"
        @dragstart="onTreeDragStart"
        @update:expanded-keys="onUpdateExpanded"
        @update:selected-keys="onSelect"
        @search-select-file="(path: string) => openFile(path)"
      />
      <SandboxPreviewPane
        v-model:tabbar-ref="tabbarRef"
        :open-tabs="openTabs"
        :selected-path="selectedPath"
        :preview="preview"
        :preview-meta="previewMeta"
        :preview-loading="previewLoading"
        :breadcrumbs="breadcrumbs"
        :is-markdown-file="isMarkdownFile"
        :md-raw-mode="mdRawMode"
        :can-copy-preview="canCopyPreview"
        :copy-done="copyDone"
        :show-markdown-rendered="showMarkdownRendered"
        :preview-lang-class="previewLangClass"
        :preview-code-html="previewCodeHtml"
        :focus-line="focusLine"
        :display-path="displayPath"
        @activate-tab="activateTab"
        @close-tab="closeTab"
        @toggle-md-raw-mode="toggleMdRawMode"
        @copy-preview="copyPreview"
        @add-selection="onAddSelection"
      />
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
  /* 与 ChatView .ws-drawer-toggle 同高：6+22+6，保证开/关按钮垂直对齐 */
  flex-shrink: 0;
  box-sizing: border-box;
  height: 34px;
  padding: 0 8px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--sun-border);
}

.drawer-head-top {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.drawer-title {
  margin: 0;
  font-size: var(--sun-font-base);
  font-weight: 550;
  color: var(--sun-text);
}

.ws-banner {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  font-size: var(--sun-font-sm);
  font-weight: 500;
  border-bottom: 1px solid var(--sun-border);
}

.ws-banner-text {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ws-banner--error {
  color: #c44;
  background: color-mix(in srgb, #c44 8%, transparent);
}

.ws-banner--info {
  color: var(--sun-text-muted);
  background: color-mix(in srgb, var(--sun-text-muted) 10%, transparent);
}

.ws-no-checkout-hint {
  padding: 8px 12px;
  border-bottom: 1px solid var(--sun-border);
  color: var(--sun-text-muted);
  font-size: 12px;
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

.drawer-tabs {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  border: none;
  border-radius: 8px;
  background: transparent;
  /* 允许随抽屉变窄收缩，但文字保持常显 */
  flex: 0 1 auto;
  min-width: 0;
}

.drawer-tab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 22px;
  padding: 0 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm, 12px);
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.drawer-tab-icon {
  flex-shrink: 0;
}

/* 分支名 / 写操作模式按钮文字保持常显；分支名的收缩由 ChatView 依据 slot compact 切换文字内容 */
.drawer-tab-label {
  white-space: nowrap;
}

.drawer-tab:hover {
  color: var(--sun-text);
}

.drawer-tab.active {
  background: color-mix(in srgb, var(--sun-text) 8%, var(--sun-black));
  color: var(--sun-text-secondary);
}

.drawer-tab.active .drawer-tab-label {
  font-weight: 600;
}

.drawer-tab.active .drawer-tab-icon :deep(svg) {
  stroke-width: 2.2;
}

.drawer-tab:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.icon-btn:hover {
  color: var(--sun-text);
}

.drawer-close {
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  padding: 2px 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
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
</style>
