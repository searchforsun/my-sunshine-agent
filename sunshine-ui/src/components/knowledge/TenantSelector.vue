<script setup lang="ts">
import { computed, ref } from 'vue'
import { NIcon, NPopover } from 'naive-ui'
import { CheckmarkOutline, ChevronDownOutline, BusinessOutline } from '@vicons/ionicons5'
import { TENANT_OPTIONS, findTenantOption, type TenantId, type TenantOption } from '../../api/tenants'

const props = defineProps<{
  modelValue: TenantId
  disabled?: boolean
  /** compact：知识库页；block：设置页 */
  variant?: 'compact' | 'block'
  /** 顶部筛选：菜单首项显示「全部租户」（值 ''） */
  includeAll?: boolean
  /** 自定义菜单项（默认 TENANT_OPTIONS），供合并已配置租户 */
  options?: TenantOption[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: TenantId]
}>()

const variant = computed(() => props.variant ?? 'compact')
const showMenu = ref(false)
const menuOptions = computed(() => props.options ?? TENANT_OPTIONS)
const current = computed(() => {
  if (!props.modelValue) {
    if (props.includeAll) return { value: '', label: '全部租户', description: '全部租户' }
    return { value: '', label: '选择租户', description: '' }
  }
  return findTenantOption(props.modelValue)
})

const COMPACT_MENU_WIDTH = 304

const popoverWidth = computed(() => (variant.value === 'block' ? 'trigger' : COMPACT_MENU_WIDTH))

function select(value: TenantId) {
  emit('update:modelValue', value)
  showMenu.value = false
}

function onShowUpdate(next: boolean) {
  if (props.disabled) return
  showMenu.value = next
}
</script>

<template>
  <div class="tenant-dropdown-root" :class="`variant-${variant}`">
    <NPopover
      :show="showMenu"
      trigger="click"
      content-class="tenant-selector-popover"
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
          class="tenant-selector"
          :class="`variant-${variant}`"
          :disabled="disabled"
          :title="current.description ?? current.label"
        >
          <span class="tenant-leading">
            <NIcon class="tenant-icon" :component="BusinessOutline" :size="14" />
            <span class="tenant-label">{{ current.label }}</span>
          </span>
          <NIcon class="tenant-chevron" :component="ChevronDownOutline" :size="12" />
        </button>
      </template>

      <div class="tenant-menu" :class="{ 'tenant-menu--compact': variant === 'compact' }" role="listbox" aria-label="租户">
        <button
          v-if="includeAll"
          type="button"
          role="option"
          class="tenant-menu-item"
          :class="{ 'is-selected': modelValue === '' }"
          :aria-selected="modelValue === ''"
          @click="select('')"
        >
          <NIcon class="tenant-menu-icon" :component="BusinessOutline" :size="18" />
          <span class="tenant-menu-text">
            <span class="tenant-menu-title">全部租户</span>
          </span>
          <span class="tenant-menu-check-slot" aria-hidden="true">
            <NIcon
              v-if="modelValue === ''"
              class="tenant-menu-check"
              :component="CheckmarkOutline"
              :size="18"
            />
          </span>
        </button>
        <button
          v-for="opt in menuOptions"
          :key="opt.value"
          type="button"
          role="option"
          class="tenant-menu-item"
          :class="{ 'is-selected': modelValue === opt.value }"
          :aria-selected="modelValue === opt.value"
          @click="select(opt.value)"
        >
          <NIcon class="tenant-menu-icon" :component="BusinessOutline" :size="18" />
          <span class="tenant-menu-text">
            <span class="tenant-menu-title">{{ opt.label }}</span>
            <span v-if="opt.description" class="tenant-menu-desc">{{ opt.description }}</span>
          </span>
          <span class="tenant-menu-check-slot" aria-hidden="true">
            <NIcon
              v-if="modelValue === opt.value"
              class="tenant-menu-check"
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
.tenant-dropdown-root {
  display: inline-flex;
  max-width: 100%;
}

.tenant-dropdown-root.variant-block {
  display: block;
  width: 100%;
}

.tenant-dropdown-root.variant-block :deep(> *) {
  display: block;
  width: 100%;
}

.tenant-selector {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  cursor: pointer;
  flex-shrink: 0;
  max-width: 100%;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.tenant-selector.variant-block {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border-radius: var(--radius-md, 10px);
  justify-content: space-between;
  background: var(--n-color, var(--sun-black));
  color: var(--sun-text, #ececec);
  font-size: var(--sun-font-base, 14px);
}

.tenant-selector:hover:not(:disabled) {
  border-color: var(--sun-border-light, #ccc);
  color: var(--sun-text, #ececec);
}

.tenant-selector:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.tenant-leading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.tenant-icon,
.tenant-chevron {
  flex-shrink: 0;
  color: currentColor;
}

.tenant-icon {
  opacity: 0.9;
}

.tenant-label {
  font-weight: 500;
  white-space: nowrap;
}

.tenant-chevron {
  opacity: 0.55;
}

.tenant-menu {
  padding: 4px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, var(--sun-black));
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
}

.tenant-menu--compact {
  padding: 3px;
}

.tenant-menu--compact .tenant-menu-item {
  gap: 8px;
  padding: 7px 8px;
}

.tenant-menu--compact .tenant-menu-desc {
  font-size: var(--sun-font-sm, 12px);
}

.tenant-menu-item {
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

.tenant-menu-item:hover {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.tenant-menu-icon {
  flex-shrink: 0;
  color: var(--sun-text-secondary, #666);
}

.tenant-menu-item.is-selected .tenant-menu-icon {
  color: var(--sun-text, #ececec);
}

.tenant-menu-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.tenant-menu-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.35;
  color: var(--sun-text, #ececec);
  white-space: nowrap;
}

.tenant-menu-desc {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.45;
  color: var(--sun-text-muted, #888);
  white-space: nowrap;
}

.tenant-menu-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
}

.tenant-menu-check {
  color: var(--sun-text, #ececec);
}
</style>

<style>
.n-popover.n-popover--raw:has(.tenant-menu),
.n-popover-shared:has(.tenant-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}

.tenant-selector-popover {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}

.tenant-selector-popover .n-popover__content,
.tenant-selector-popover .v-binder-follower-content,
.v-binder-follower-content:has(.tenant-menu) {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}
</style>
