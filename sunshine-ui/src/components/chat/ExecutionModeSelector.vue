<script setup lang="ts">
import { computed, ref } from 'vue'
import { NIcon, NPopover } from 'naive-ui'
import { CheckmarkOutline, ChevronDownOutline } from '@vicons/ionicons5'
import { executionModeIcon } from '../../api/executionModeIcons'
import {
  EXECUTION_MODE_OPTIONS,
  findExecutionModeOption,
  type ExecutionPreference,
} from '../../api/executionModes'

const props = defineProps<{
  modelValue: ExecutionPreference
  disabled?: boolean
  /** compact：Chat 底栏；block：设置页等表单场景 */
  variant?: 'compact' | 'block'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ExecutionPreference]
}>()

const variant = computed(() => props.variant ?? 'compact')
const showMenu = ref(false)
const current = computed(() => findExecutionModeOption(props.modelValue))
const currentIcon = computed(() => executionModeIcon(props.modelValue))

const COMPACT_MENU_WIDTH = 252

const popoverWidth = computed(() => (variant.value === 'block' ? 'trigger' : COMPACT_MENU_WIDTH))

function select(value: ExecutionPreference) {
  emit('update:modelValue', value)
  showMenu.value = false
}

function onShowUpdate(next: boolean) {
  if (props.disabled) return
  showMenu.value = next
}
</script>

<template>
  <div class="mode-dropdown-root" :class="`variant-${variant}`">
    <NPopover
      :show="showMenu"
      trigger="click"
      content-class="execution-mode-popover"
      :placement="variant === 'block' ? 'bottom-start' : 'top-start'"
      :width="popoverWidth"
      :disabled="disabled"
      raw
      :show-arrow="false"
      @update:show="onShowUpdate"
    >
      <template #trigger>
        <button
          type="button"
          class="mode-selector"
          :class="`variant-${variant}`"
          :disabled="disabled"
          :title="current.description"
        >
          <span class="mode-leading">
            <NIcon class="mode-icon" :component="currentIcon" :size="14" />
            <span class="mode-label">{{ current.label }}</span>
          </span>
          <NIcon class="mode-chevron" :component="ChevronDownOutline" :size="12" />
        </button>
      </template>

      <div class="mode-menu" :class="{ 'mode-menu--compact': variant === 'compact' }" role="listbox" aria-label="执行模式">
        <button
          v-for="opt in EXECUTION_MODE_OPTIONS"
          :key="opt.value"
          type="button"
          role="option"
          class="mode-menu-item"
          :class="{ 'is-selected': modelValue === opt.value }"
          :aria-selected="modelValue === opt.value"
          @click="select(opt.value)"
        >
          <NIcon class="mode-menu-icon" :component="executionModeIcon(opt.value)" :size="18" />
          <span class="mode-menu-text">
            <span class="mode-menu-title">{{ opt.label }}</span>
            <span class="mode-menu-desc">{{ opt.description }}</span>
          </span>
          <span class="mode-menu-check-slot" aria-hidden="true">
            <NIcon
              v-if="modelValue === opt.value"
              class="mode-menu-check"
              :component="CheckmarkOutline"
              :size="16"
            />
          </span>
        </button>
      </div>
    </NPopover>
  </div>
</template>

<style scoped>
.mode-dropdown-root {
  display: inline-flex;
  max-width: 100%;
}

.mode-dropdown-root.variant-block {
  display: block;
  width: 100%;
}

.mode-dropdown-root.variant-block :deep(> *) {
  display: block;
  width: 100%;
}

.mode-selector {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--sun-border, #e0e0e0);
  border-radius: 999px;
  background: color-mix(in srgb, var(--sun-bg, #fff) 90%, var(--sun-text-muted, #888));
  color: var(--sun-text-secondary, #666);
  font-size: var(--sun-font-sm, 12px);
  cursor: pointer;
  flex-shrink: 0;
  max-width: 100%;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.mode-selector.variant-block {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border-radius: var(--radius-md, 10px);
  justify-content: space-between;
  background: var(--n-color, #fff);
  color: var(--sun-text, #212121);
  font-size: var(--sun-font-base, 14px);
}

.mode-selector:hover:not(:disabled) {
  border-color: var(--sun-border-light, #ccc);
  color: var(--sun-text, #212121);
}

.mode-selector:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.mode-leading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.mode-icon {
  flex-shrink: 0;
  opacity: 0.9;
}

.mode-label {
  font-weight: 500;
  white-space: nowrap;
}

.mode-chevron {
  flex-shrink: 0;
  opacity: 0.55;
}

.mode-menu {
  padding: 4px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, #fff);
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
}

.mode-menu--compact {
  padding: 3px;
}

.mode-menu--compact .mode-menu-item {
  gap: 8px;
  padding: 7px 8px;
}

.mode-menu--compact .mode-menu-desc {
  font-size: 11px;
}

.mode-menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: calc(var(--radius-md, 10px) - 2px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}

.mode-menu-item:hover {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.mode-menu-item.is-selected {
  background: var(--sun-accent-muted, rgba(0, 0, 0, 0.04));
}

.mode-menu-icon {
  flex-shrink: 0;
  color: var(--sun-text-secondary, #666);
}

.mode-menu-item.is-selected .mode-menu-icon {
  color: var(--sun-text, #212121);
}

.mode-menu-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.mode-menu-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.35;
  color: var(--sun-text, #212121);
}

.mode-menu-desc {
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.4;
  color: var(--sun-text-muted, #888);
}

.mode-menu-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 16px;
}

.mode-menu-check {
  color: var(--sun-text, #212121);
}
</style>

<style>
/* raw Popover 外层仍有矩形 box-shadow，会话页向上弹出时底部会「露角」 */
.n-popover.n-popover--raw:has(.mode-menu),
.n-popover-shared:has(.mode-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}

.execution-mode-popover {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}

.execution-mode-popover .n-popover__content,
.execution-mode-popover .v-binder-follower-content,
.v-binder-follower-content:has(.mode-menu) {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}
</style>
