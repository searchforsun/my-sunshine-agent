<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { NIcon, NButton } from 'naive-ui'
import { RefreshOutline, WarningOutline } from '@vicons/ionicons5'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'
import { sandboxWorkspaceRefresh } from '../../composables/sandboxWorkspaceRefresh'
import { useWriteHitlMode } from '../../composables/useWriteHitlMode'
import { useSandboxFileTree } from '../../composables/useSandboxFileTree'
import { useSandboxPreviewTabs } from '../../composables/useSandboxPreviewTabs'
import { syncWorkspace } from '../../api/workspaces'
import WriteHitlModeSelector from './WriteHitlModeSelector.vue'
import DrawerCollapseIcon from '../icons/DrawerCollapseIcon.vue'
import SandboxFileTreePane from './SandboxFileTreePane.vue'
import SandboxPreviewPane from './SandboxPreviewPane.vue'

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
  drawerWidth,
  canResizeDrawer,
  treeWidth,
  canResizeTree,
  onResizePointerDown,
  onTreeResizePointerDown,
} = useSandboxWorkspaceDrawer()

const { mode: writeHitlMode } = useWriteHitlMode(() => state.conversationId)


let openFile: (path: string) => void | Promise<void> = () => {}
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
  onOpenFile: (path) => openFile(path),
})

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
  canCopyPreview,
  breadcrumbs,
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
  selectedKeys,
})
openFile = previewOpenFile

async function refresh() {
  // 重建文件树（不主动触发 git 操作）
  const keepTabs = openTabs.value.map((t) => t.path)
  const keepActive = selectedPath.value
  clearCache()
  await loadRoots()
  if (keepTabs.length) {
    openTabs.value = keepTabs.map((path) => ({ path }))
    if (keepActive) await previewOpenFile(keepActive)
  }
}

/** 工作区代码未正确克隆时的重试：调用 sync API（远端拉取或重新 clone） */
const retrying = ref(false)
const retryError = ref('')
const wsSynced = ref(false)

async function retryClone() {
  if (!props.workspaceId) {
    retryError.value = '未选择工作区'
    return
  }
  retrying.value = true
  retryError.value = ''
  try {
    const result = await syncWorkspace(props.workspaceId)
    wsSynced.value = true
    retryError.value = ''
    // 工作区级别文件树（无需等待会话创建）
    try { await loadRoots() } catch { /* ok */ }
  } catch (e) {
    wsSynced.value = false
    const msg = (e as any)?.message || String(e)
    retryError.value = msg || '同步失败，请检查 Git 配置和仓库地址'
  } finally {
    retrying.value = false
  }
}

/** 工作区变化时重置状态 */
watch(() => props.workspaceId, () => {
  wsSynced.value = false
  retryError.value = ''
})

/** checkout 切换（新建 worktree / 选中其他 checkout）时重载文件树，根指向新项目目录 */
watch(() => props.checkoutId, () => {
  if (!state.open) return
  clearCache()
  void loadRoots()
})

/** 是否任务工作区（带 workspaceId）；chat 沙箱不显示 git 相关提示 */
const isTaskWorkspace = computed(() => !!props.workspaceId)

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

async function refreshBranch(scope: 'workspace' | 'skills') {
  const prefix = scope === 'workspace' ? '/workspace' : '/skills'
  clearCacheUnder(prefix)
  await reloadBranch(prefix)
  const keepActive = selectedPath.value
  if (keepActive?.startsWith(prefix)) {
    await previewOpenFile(keepActive)
  }
}

function toggleMdRawMode() {
  mdRawMode.value = !mdRawMode.value
}

watch(
  () => [state.open, state.conversationId, state.focusPath] as const,
  ([open, convId, focus], prev) => {
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
      if (focus) await revealPath(focus)
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
        <h3 class="drawer-title">工作区</h3>
        <span class="readonly-badge">只读</span>
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

    <!-- 工作区未就绪 / 重试反馈（仅任务工作区；chat 沙箱不显示 git 提示） -->
    <div
      v-if="isTaskWorkspace && !noCheckoutYet && ((workspaceEmpty && !treeLoading) || (retryError && wsSynced))"
      class="ws-retry-banner"
      :class="{ 'is-success': wsSynced }"
    >
      <NIcon :component="WarningOutline" :size="14" />
      <div class="ws-retry-body">
        <span>{{ retryError || '工作区加载失败，请重试' }}</span>
      </div>
      <NButton v-if="!wsSynced" size="tiny" type="primary" :loading="retrying" @click="retryClone">重试</NButton>
    </div>
    <!-- 未选择分支（新任务未发送）：提示选择分支并发送以拉取代码 -->
    <div v-if="isTaskWorkspace && noCheckoutYet" class="ws-no-checkout-hint">
      选择分支并发送消息后拉取代码到工作区
    </div>

    <div class="explorer">
      <SandboxFileTreePane
        :tree-width="treeWidth"
        :can-resize-tree="canResizeTree"
        :tree-loading="treeLoading"
        :error-text="errorText"
        :tree-data="treeData"
        :expanded-keys="expandedKeys"
        :selected-keys="selectedKeys"
        :on-tree-load="onLoad"
        @tree-resize-pointer-down="onTreeResizePointerDown"
        @dragstart="onTreeDragStart"
        @update:expanded-keys="onUpdateExpanded"
        @update:selected-keys="onSelect"
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
        @activate-tab="activateTab"
        @close-tab="closeTab"
        @toggle-md-raw-mode="toggleMdRawMode"
        @copy-preview="copyPreview"
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

.ws-retry-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: rgba(255, 193, 7, 0.08);
  border-bottom: 1px solid rgba(255, 193, 7, 0.2);
  color: var(--sun-text-muted);
  font-size: 12px;
}

.ws-retry-banner.is-success {
  background: rgba(34, 197, 94, 0.08);
  border-bottom-color: rgba(34, 197, 94, 0.2);
  color: rgba(34, 197, 94, 0.85);
}

.ws-retry-body {
  flex: 1;
  min-width: 0;
  line-height: 1.4;
  color: var(--sun-text-muted);
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
