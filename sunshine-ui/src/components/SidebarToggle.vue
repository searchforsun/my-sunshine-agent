<script setup lang="ts">
import { useSidebar } from '../composables/useSidebar'

withDefaults(
  defineProps<{
    /** inline：页头内嵌；fab：固定浮层（非 fill 页侧栏收起时） */
    variant?: 'inline' | 'fab'
  }>(),
  { variant: 'inline' },
)

const { sidebarVisible, toggleSidebar } = useSidebar()
</script>

<template>
  <button
    type="button"
    :class="variant === 'fab' ? 'sidebar-toggle-fab' : 'sidebar-toggle'"
    :title="sidebarVisible ? '隐藏侧栏' : '显示侧栏'"
    :aria-label="sidebarVisible ? '隐藏侧栏' : '显示侧栏'"
    @click="toggleSidebar"
  >
    <svg
      v-if="sidebarVisible"
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      aria-hidden="true"
    >
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <line x1="9" y1="3" x2="9" y2="21" />
      <polyline points="14 8 11 12 14 16" />
    </svg>
    <svg
      v-else
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      aria-hidden="true"
    >
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <line x1="9" y1="3" x2="9" y2="21" />
      <polyline points="10 8 13 12 10 16" />
    </svg>
  </button>
</template>

<style scoped>
.sidebar-toggle {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  padding: 0;
  border: none;
  outline: none;
  box-shadow: none;
  border-radius: 10px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.sidebar-toggle:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.sidebar-toggle-fab {
  position: fixed;
  top: 14px;
  left: 14px;
  z-index: 100;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, color 0.15s;
  box-shadow: var(--shadow-card);
}

.sidebar-toggle-fab:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}
</style>
