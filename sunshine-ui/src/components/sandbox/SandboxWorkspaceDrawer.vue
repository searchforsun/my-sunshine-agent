<script setup lang="ts">
import { watch } from 'vue'
import { NIcon } from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'
import { sandboxWorkspaceRefresh } from '../../composables/sandboxWorkspaceRefresh'
import { useWriteHitlMode } from '../../composables/useWriteHitlMode'
import { useSandboxFileTree } from '../../composables/useSandboxFileTree'
import { useSandboxPreviewTabs } from '../../composables/useSandboxPreviewTabs'
import WriteHitlModeSelector from './WriteHitlModeSelector.vue'
import DrawerCollapseIcon from '../icons/DrawerCollapseIcon.vue'
import SandboxFileTreePane from './SandboxFileTreePane.vue'
import SandboxPreviewPane from './SandboxPreviewPane.vue'

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
  selectedKeys,
})
openFile = previewOpenFile

async function refresh() {
  const keepTabs = openTabs.value.map((t) => t.path)
  const keepActive = selectedPath.value
  clearCache()
  await loadRoots()
  if (keepTabs.length) {
    openTabs.value = keepTabs.map((path) => ({ path }))
    if (keepActive) await previewOpenFile(keepActive)
  }
}

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
    const prevConv = prev?.[1]
    if (prevConv && prevConv !== convId) {
      resetTabsOnConversationChange()
    }
    void (async () => {
      await loadRoots()
      if (focus) await revealPath(focus)
    })()
  },
)

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
          <button type="button" class="drawer-close" title="收起" aria-label="收起" @click="close">
            <DrawerCollapseIcon :size="16" />
          </button>
        </div>
      </div>
    </header>

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
