<script setup lang="ts">
import { NSpin, NText } from 'naive-ui'

defineProps<{
  title: string
  loading?: boolean
  width: number
  canResize?: boolean
}>()

const emit = defineEmits<{
  close: []
  resizePointerDown: [event: PointerEvent]
}>()
</script>

<template>
  <aside
    class="eval-report-drawer"
    role="complementary"
    aria-label="评测报告详情"
    :style="{ width: `${width}px` }"
  >
    <div
      v-if="canResize"
      class="drawer-resize-handle"
      role="separator"
      aria-orientation="vertical"
      aria-label="调整抽屉宽度"
      @pointerdown="emit('resizePointerDown', $event)"
    />
    <header class="drawer-header">
      <h3 class="drawer-title">{{ title }}</h3>
      <button type="button" class="drawer-close" aria-label="关闭" @click="emit('close')">×</button>
    </header>
    <div class="drawer-body">
      <div v-if="loading" class="drawer-loading">
        <NSpin size="small" />
        <NText depth="3">加载报告中…</NText>
      </div>
      <slot v-else />
    </div>
  </aside>
</template>

<style scoped>
.eval-report-drawer {
  position: relative;
  flex-shrink: 0;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--sun-border);
  background: var(--sun-black);
  box-shadow: -8px 0 24px color-mix(in srgb, black 8%, transparent);
}
.drawer-resize-handle {
  position: absolute;
  left: -5px;
  top: 0;
  bottom: 0;
  width: 10px;
  z-index: 5;
  cursor: col-resize;
  touch-action: none;
}
.drawer-resize-handle::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 0;
  bottom: 0;
  width: 2px;
  border-radius: 1px;
  background: transparent;
  transition: background 0.15s;
}
.drawer-resize-handle:hover::after {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 55%, transparent);
}
.drawer-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--sun-border);
}
.drawer-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drawer-close {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: 22px;
  line-height: 1;
  width: 28px;
  height: 28px;
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.drawer-close:hover {
  color: var(--sun-text);
  background: var(--sun-row-hover);
}
.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0;
}
.drawer-body :deep(.result-view) {
  border: none;
  border-radius: 0;
}
.drawer-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px 14px;
}
</style>

<style>
body.eval-drawer-resizing {
  cursor: col-resize !important;
  user-select: none !important;
}
body.eval-drawer-resizing .drawer-resize-handle::after {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 55%, transparent);
}
</style>
