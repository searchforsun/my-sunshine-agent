<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  summary: string
  /** 折叠态展示的补充概要（参数、链路、错误信息等） */
  detail?: string
  resolved?: boolean
  /** 初始是否折叠（已决态默认 true，待确认默认 false） */
  defaultCollapsed?: boolean
}>(), {
  detail: '',
  resolved: false,
  defaultCollapsed: false,
})

const collapsed = ref(props.defaultCollapsed)

watch(
  () => props.defaultCollapsed,
  (v) => {
    collapsed.value = v
  },
)

watch(
  () => props.resolved,
  (v) => {
    if (v) collapsed.value = true
  },
)

function toggle(): void {
  collapsed.value = !collapsed.value
}

/** 折叠态：标题与概要合并为一行（与 HITL / Recovery 确认框一致） */
const collapsedLine = computed(() => {
  const detail = props.detail?.trim()
  if (!detail) return props.summary
  return `${props.summary} · ${detail}`
})
</script>

<template>
  <div
    class="collapsible-confirm"
    :class="{
      'is-resolved': resolved,
      'is-awaiting': !resolved,
      'is-collapsed': collapsed,
      'is-expanded': !collapsed,
    }"
  >
    <button
      type="button"
      class="confirm-gutter"
      :aria-expanded="!collapsed"
      aria-label="展开或收起"
      @click="toggle"
    >
      <svg
        class="confirm-chevron"
        width="9"
        height="9"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.5"
        stroke-linecap="round"
        aria-hidden="true"
      >
        <polyline points="9 18 15 12 9 6" />
      </svg>
    </button>
    <div class="confirm-card">
      <button
        type="button"
        class="collapse-header"
        :aria-expanded="!collapsed"
        @click="toggle"
      >
        <span v-if="collapsed" class="collapse-line">{{ collapsedLine }}</span>
        <span v-else class="collapse-summary">{{ summary }}</span>
      </button>
      <div v-show="!collapsed" class="collapse-expand">
        <div class="collapse-body">
          <slot />
        </div>
        <div v-if="$slots.footer && !resolved" class="collapse-footer">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.collapsible-confirm {
  --op-gutter: 12px;
  --confirm-chevron-offset: calc(var(--op-gutter) + 4px);
  position: relative;
  margin: 6px 0;
  margin-left: var(--confirm-inset-left, 0);
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.confirm-gutter {
  position: absolute;
  left: calc(-1 * var(--confirm-chevron-offset));
  top: 0;
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  width: var(--op-gutter);
  height: 100%;
  padding-top: 4px;
  margin: 0;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.collapsible-confirm.is-collapsed .confirm-gutter {
  align-items: center;
  padding-top: 0;
}

.confirm-gutter:hover .confirm-chevron {
  opacity: 0.75;
}

.confirm-chevron {
  flex-shrink: 0;
  color: var(--sun-text-muted);
  opacity: 0.5;
  transition: transform 0.15s ease;
}

.collapsible-confirm.is-expanded .confirm-chevron {
  transform: rotate(90deg);
}

.confirm-card {
  min-width: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-black);
}

.collapse-line,
.collapse-summary {
  flex: 1;
  min-width: 0;
  line-height: 1.35;
  font-weight: 450;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.collapse-header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
}

.collapsible-confirm.is-collapsed .collapse-header {
  padding: 6px 10px;
  min-height: 28px;
}

.collapsible-confirm.is-expanded .collapse-header {
  padding-bottom: 6px;
}

.collapse-expand {
  display: flex;
  flex-direction: column;
  max-height: min(40vh, 320px);
}

.collapse-body {
  flex: 1;
  min-height: 0;
  padding: 0 12px 10px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.collapse-expand:not(:has(.collapse-footer)) .collapse-body {
  padding-bottom: 10px;
}

.collapse-footer {
  flex-shrink: 0;
  padding: 4px 10px 10px;
}
</style>
