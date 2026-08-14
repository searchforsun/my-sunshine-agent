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
        <!-- 场景图标：工具调用确认（线条扳手，stroke 描边非实心） -->
        <svg
          v-if="icon === 'tool'"
          class="confirm-list-icon"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"
            fill="none"
            stroke="currentColor"
            stroke-width="1.3"
            stroke-linecap="round"
            stroke-linejoin="round"
            vector-effect="non-scaling-stroke"
            transform="translate(-1.17 -0.67) scale(0.758)"
          />
        </svg>
        <!-- 节点失败恢复（dsh refresh 刷新环） -->
        <svg
          v-else-if="icon === 'recovery'"
          class="confirm-list-icon"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M7.92136 0.349152C10.3744 0.349234 12.5564 1.5052 13.9557 3.29894L15.1281 2.12759C15.3303 1.92546 15.6767 2.06943 15.6767 2.35538V5.53923C15.6766 5.71626 15.5329 5.85976 15.3559 5.86002H12.171C11.8854 5.8597 11.7426 5.51465 11.9443 5.31249L12.9641 4.29056C11.8237 2.74305 9.98908 1.74106 7.92136 1.74097C4.46436 1.74097 1.66233 4.543 1.66233 8C1.66233 11.457 4.46436 14.259 7.92136 14.259C11.3782 14.2589 14.1804 11.4569 14.1804 8H15.5722C15.5722 12.2251 12.1465 15.6507 7.92136 15.6508C3.69614 15.6508 0.270508 12.2252 0.270508 8C0.270508 3.77478 3.69614 0.349152 7.92136 0.349152Z"
            fill="currentColor"
          />
        </svg>
        <!-- 计划执行确认（dsh checklist 圆点清单） -->
        <svg
          v-else
          class="confirm-list-icon"
          viewBox="0 0 16 16"
          fill="none"
          aria-hidden="true"
        >
          <g transform="scale(1.14286)">
            <path d="M13.3277 9.69629V10.976H7.28086V9.69629H13.3277Z" fill="currentColor" />
            <path d="M13.3277 2.97256V4.25225H7.28086V2.97256H13.3277Z" fill="currentColor" />
            <path
              d="M4.64512 10.336C4.64505 9.62755 4.07081 9.05322 3.3623 9.05322C2.65386 9.05329 2.07956 9.62759 2.07949 10.336C2.07949 11.0445 2.65382 11.6188 3.3623 11.6188C4.07085 11.6188 4.64512 11.0446 4.64512 10.336ZM5.92559 10.336C5.92559 11.7515 4.77777 12.8993 3.3623 12.8993C1.94689 12.8993 0.799805 11.7515 0.799805 10.336C0.799871 8.92066 1.94693 7.7736 3.3623 7.77354C4.77773 7.77354 5.92552 8.92062 5.92559 10.336Z"
              fill="currentColor"
            />
            <path
              d="M4.64531 3.6123C4.6453 2.90382 4.07098 2.32949 3.3625 2.32949C2.65403 2.32951 2.0797 2.90383 2.07969 3.6123C2.07969 4.32079 2.65402 4.8951 3.3625 4.89512C4.07099 4.89512 4.64531 4.3208 4.64531 3.6123ZM5.925 3.6123C5.925 5.02772 4.77792 6.1748 3.3625 6.1748C1.9471 6.17479 0.8 5.02771 0.8 3.6123C0.800013 2.19691 1.9471 1.04982 3.3625 1.0498C4.77791 1.0498 5.92499 2.1969 5.925 3.6123Z"
              fill="currentColor"
            />
          </g>
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
