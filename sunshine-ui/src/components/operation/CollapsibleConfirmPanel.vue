<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  summary: string
  /** 折叠态展示的补充概要（参数、链路、错误信息等） */
  detail?: string
  resolved?: boolean
  /** 初始是否折叠（默认恒折叠，点击展开；已决态无操作仅展示） */
  defaultCollapsed?: boolean
  /** 行首场景图标：tool=工具调用确认 / recovery=节点失败恢复 / approval=计划执行确认 */
  icon?: 'tool' | 'recovery' | 'approval'
}>(), {
  detail: '',
  resolved: false,
  defaultCollapsed: true,
  icon: 'tool',
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
    <div class="confirm-card">
      <button
        type="button"
        class="collapse-header"
        :aria-expanded="!collapsed"
        @click="toggle"
      >
        <!-- 场景图标：工具调用确认（扳手） -->
        <svg
          v-if="icon === 'tool'"
          class="confirm-list-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
        </svg>
        <!-- 节点失败恢复（重试） -->
        <svg
          v-else-if="icon === 'recovery'"
          class="confirm-list-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <polyline points="23 4 23 10 17 10" />
          <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10" />
        </svg>
        <!-- 计划执行确认（清单+对勾） -->
        <svg
          v-else
          class="confirm-list-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
          <rect x="8" y="2" width="8" height="4" rx="1" />
          <path d="m9 14 2 2 4-4" />
        </svg>
        <svg
          class="confirm-chevron"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
        <span v-if="collapsed" class="collapse-line">{{ collapsedLine }}</span>
        <span v-else class="collapse-summary">{{ summary }}</span>
      </button>
      <div class="collapse-expand" :class="{ 'is-visible': !collapsed }">
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
  margin: 6px 0;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
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

/* 行首场景图标：常驻；展开态切换为箭头（尺寸同宽避免文字左右移动） */
.confirm-list-icon {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  opacity: 0.72;
}

.confirm-chevron {
  display: none;
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  box-sizing: border-box;
  padding: 1px;
  color: var(--sun-text-secondary);
  transition: transform 0.15s ease;
}

.collapsible-confirm.is-expanded .confirm-list-icon {
  display: none;
}

.collapsible-confirm.is-expanded .confirm-chevron {
  display: block;
}

/* 折叠态右箭头 >，展开态上箭头 ^ */
.collapsible-confirm.is-collapsed .confirm-chevron {
  transform: rotate(0deg);
}

.collapsible-confirm.is-expanded .confirm-chevron {
  transform: rotate(-90deg);
}

.collapsible-confirm.is-collapsed .collapse-header {
  padding: 6px 10px;
  min-height: 28px;
}

.collapsible-confirm.is-expanded .collapse-header {
  padding-bottom: 6px;
}

.collapse-expand {
  display: none;
  flex-direction: column;
  max-height: min(40vh, 320px);
}

/* 默认折叠成一行：仅点击展开（.is-expanded）后显示内部，hover 不自动展开 */
.collapsible-confirm.is-expanded .collapse-expand {
  display: flex;
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
