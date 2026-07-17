<script setup lang="ts">
import { h } from 'vue'
import { NIcon, NTree, type TreeDragInfo, type TreeOption } from 'naive-ui'
import { ChevronForwardOutline } from '@vicons/ionicons5'

const props = defineProps<{
  treeWidth: number
  canResizeTree: boolean
  treeLoading: boolean
  errorText: string
  treeData: TreeOption[]
  expandedKeys: string[]
  selectedKeys: string[]
  onTreeLoad: (option: TreeOption) => Promise<void>
}>()

const emit = defineEmits<{
  treeResizePointerDown: [ev: PointerEvent]
  dragstart: [info: TreeDragInfo]
  'update:expanded-keys': [keys: Array<string | number>]
  'update:selected-keys': [keys: Array<string | number>, option: Array<TreeOption | null>]
}>()

function denyTreeDrop() {
  return false
}

function treeNodeProps({ option }: { option: TreeOption }) {
  return {
    title: String((option as TreeOption & { path?: string }).path || option.key),
  }
}

function onLoad(option: TreeOption) {
  return props.onTreeLoad(option)
}
</script>

<template>
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
        @dragstart="emit('dragstart', $event)"
        @update:expanded-keys="emit('update:expanded-keys', $event)"
        @update:selected-keys="(keys, option) => emit('update:selected-keys', keys, option)"
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

.pane-hint,
.pane-error {
  margin: 8px 4px;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.pane-error {
  color: var(--sun-danger, #e07070);
}
</style>
