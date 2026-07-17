<script setup lang="ts">
import { computed, ref } from 'vue'
import { NIcon, NPopover } from 'naive-ui'
import { CheckmarkOutline, ChevronDownOutline, ShieldCheckmarkOutline } from '@vicons/ionicons5'
import {
  WRITE_HITL_MODE_OPTIONS,
  findWriteHitlModeOption,
  type WriteHitlMode,
} from '../../api/writeHitlModes'

const props = defineProps<{
  modelValue: WriteHitlMode
  disabled?: boolean
  /** compact：工作区顶栏；block：账号设置 */
  variant?: 'compact' | 'block'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: WriteHitlMode]
}>()

const variant = computed(() => props.variant ?? 'compact')
const showMenu = ref(false)
const current = computed(() => findWriteHitlModeOption(props.modelValue))
const COMPACT_MENU_WIDTH = 280
const popoverWidth = computed(() => (variant.value === 'block' ? 'trigger' : COMPACT_MENU_WIDTH))

function select(value: WriteHitlMode) {
  emit('update:modelValue', value)
  showMenu.value = false
}

function onShowUpdate(next: boolean) {
  if (props.disabled) return
  showMenu.value = next
}
</script>

<template>
  <div class="write-hitl-root" :class="`variant-${variant}`">
    <NPopover
      :show="showMenu"
      trigger="click"
      content-class="write-hitl-popover"
      :placement="variant === 'block' ? 'bottom-start' : 'bottom-end'"
      :width="popoverWidth"
      :disabled="disabled"
      raw
      :show-arrow="false"
      @update:show="onShowUpdate"
    >
      <template #trigger>
        <button
          type="button"
          class="write-hitl-trigger"
          :class="`variant-${variant}`"
          :disabled="disabled"
          :title="current.description"
        >
          <span class="write-hitl-leading">
            <NIcon class="write-hitl-icon" :component="ShieldCheckmarkOutline" :size="14" />
            <span class="write-hitl-label">{{ current.label }}</span>
          </span>
          <NIcon class="write-hitl-chevron" :component="ChevronDownOutline" :size="12" />
        </button>
      </template>

      <div class="write-hitl-menu" role="listbox" aria-label="写操作确认">
        <button
          v-for="opt in WRITE_HITL_MODE_OPTIONS"
          :key="opt.value"
          type="button"
          role="option"
          class="write-hitl-item"
          :class="{ 'is-selected': modelValue === opt.value }"
          :aria-selected="modelValue === opt.value"
          @click="select(opt.value)"
        >
          <span class="write-hitl-text">
            <span class="write-hitl-title">{{ opt.label }}</span>
            <span class="write-hitl-desc">{{ opt.description }}</span>
          </span>
          <span class="write-hitl-check-slot" aria-hidden="true">
            <NIcon
              v-if="modelValue === opt.value"
              class="write-hitl-check"
              :component="CheckmarkOutline"
              :size="18"
            />
          </span>
        </button>
      </div>
    </NPopover>
  </div>
</template>

<style scoped>
.write-hitl-root {
  display: inline-flex;
  max-width: 100%;
  min-width: 0;
}

.write-hitl-root.variant-block {
  display: block;
  width: 100%;
}

.write-hitl-root.variant-block :deep(> *) {
  display: block;
  width: 100%;
}

.write-hitl-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  max-width: 100%;
  padding: 0 10px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: transparent;
  color: var(--sun-text-secondary);
  font: inherit;
  font-size: var(--sun-font-sm, 12px);
  cursor: pointer;
  flex-shrink: 0;
  transition: border-color 0.15s, color 0.15s;
}

.write-hitl-trigger.variant-block {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border-radius: var(--radius-md, 10px);
  justify-content: space-between;
  background: var(--n-color, #fff);
  color: var(--sun-text, #212121);
  font-size: var(--sun-font-base, 14px);
}

.write-hitl-trigger:hover:not(:disabled) {
  border-color: var(--sun-border-light, #ccc);
  color: var(--sun-text, #212121);
}

.write-hitl-trigger:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.write-hitl-leading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.write-hitl-icon,
.write-hitl-chevron {
  flex-shrink: 0;
  color: currentColor;
}

.write-hitl-icon {
  opacity: 0.9;
}

.write-hitl-label {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.write-hitl-chevron {
  opacity: 0.55;
}

.write-hitl-menu {
  padding: 3px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, #fff);
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
}

.write-hitl-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: calc(var(--radius-md, 10px) - 2px);
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}

.write-hitl-item:hover {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.write-hitl-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.write-hitl-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.35;
  color: var(--sun-text, #212121);
  white-space: nowrap;
}

.write-hitl-desc {
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  color: var(--sun-text-muted, #888);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.write-hitl-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
}

.write-hitl-check {
  color: var(--sun-text, #212121);
}
</style>

<style>
.n-popover.n-popover--raw:has(.write-hitl-menu),
.n-popover-shared:has(.write-hitl-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}

.write-hitl-popover {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}

.write-hitl-popover .n-popover__content,
.write-hitl-popover .v-binder-follower-content,
.v-binder-follower-content:has(.write-hitl-menu) {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}
</style>
