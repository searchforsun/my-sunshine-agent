<script setup lang="ts">
import { computed, ref } from 'vue'
import { NIcon, NPopover } from 'naive-ui'
import { AddOutline, CheckmarkOutline, ChevronDownOutline, LibraryOutline } from '@vicons/ionicons5'
import type { KnowledgeBase } from '../../api/ragAdmin'

const props = withDefaults(
  defineProps<{
    kbs: KnowledgeBase[]
    modelValue: string | null
    loading?: boolean
    /** Chat 底栏传 false 隐藏「新建知识库」；知识库页默认展示 */
    showCreate?: boolean
    /** compact：Chat 底栏；block：知识库页顶栏 */
    variant?: 'compact' | 'block'
    /** 底栏碰撞时仅显示图标 */
    iconOnly?: boolean
  }>(),
  { showCreate: true, iconOnly: false },
)

const showCreateButton = computed(() => props.showCreate)
const variant = computed(() => props.variant ?? 'compact')
const popoverPlacement = computed(() => (variant.value === 'block' ? 'bottom-start' : 'top-start'))

const COMPACT_MENU_WIDTH = 304
const popoverWidth = computed(() => (variant.value === 'block' ? 'trigger' : COMPACT_MENU_WIDTH))

const emit = defineEmits<{
  'update:modelValue': [value: string]
  create: []
}>()

const showMenu = ref(false)

const current = computed(() => {
  if (!props.modelValue) return null
  return props.kbs.find((kb) => kb.kbId === props.modelValue) ?? null
})

const currentLabel = computed(() => {
  if (!current.value) return '选择知识库'
  return current.value.displayName
})

function select(kbId: string) {
  emit('update:modelValue', kbId)
  showMenu.value = false
}

function handleCreate() {
  showMenu.value = false
  emit('create')
}

function onShowUpdate(next: boolean) {
  if (props.loading) return
  showMenu.value = next
}
</script>

<template>
  <div class="kb-dropdown-root" :class="[`variant-${variant}`, { 'is-icon-only': iconOnly }]">
    <NPopover
      :show="showMenu"
      trigger="click"
      content-class="kb-selector-popover"
      :placement="popoverPlacement"
      :width="popoverWidth"
      :disabled="loading"
      raw
      :show-arrow="false"
      @update:show="onShowUpdate"
    >
      <template #trigger>
        <button
          type="button"
          class="kb-trigger"
          :disabled="loading"
          :title="current?.displayName ?? current?.kbId ?? '选择知识库'"
        >
          <span class="kb-leading">
            <NIcon class="kb-icon" :component="LibraryOutline" :size="14" />
            <span class="kb-label">{{ currentLabel }}</span>
          </span>
          <NIcon class="kb-chevron" :component="ChevronDownOutline" :size="12" />
        </button>
      </template>

      <div class="kb-menu" role="listbox" aria-label="知识库">
        <div v-if="kbs.length === 0" class="kb-menu-empty">暂无知识库</div>
        <button
          v-for="kb in kbs"
          :key="kb.kbId"
          type="button"
          role="option"
          class="kb-menu-item"
          :class="{ 'is-selected': modelValue === kb.kbId }"
          :aria-selected="modelValue === kb.kbId"
          @click="select(kb.kbId)"
        >
          <NIcon class="kb-menu-icon" :component="LibraryOutline" :size="18" />
          <span class="kb-menu-text">
            <span class="kb-menu-title">
              {{ kb.displayName }}
              <span v-if="kb.isDefault" class="kb-menu-tag">默认</span>
            </span>
            <span class="kb-menu-desc">{{ kb.kbId }}</span>
          </span>
          <span class="kb-menu-check-slot" aria-hidden="true">
            <NIcon
              v-if="modelValue === kb.kbId"
              class="kb-menu-check"
              :component="CheckmarkOutline"
              :size="18"
            />
          </span>
        </button>
        <div v-if="showCreateButton" class="kb-menu-divider" />
        <button
          v-if="showCreateButton"
          type="button"
          class="kb-menu-item kb-menu-item--action"
          @click="handleCreate"
        >
          <NIcon class="kb-menu-icon" :component="AddOutline" :size="18" />
          <span class="kb-menu-title">新建知识库</span>
        </button>
      </div>
    </NPopover>
  </div>
</template>

<style scoped>
.kb-dropdown-root {
  display: inline-flex;
  max-width: 100%;
}

.kb-dropdown-root.variant-block {
  display: block;
  width: 100%;
}

.kb-dropdown-root.variant-block :deep(> *) {
  display: block;
  width: 100%;
}

.kb-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 10px;
  border: none;
  border-radius: var(--radius-lg, 12px);
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm, 12px);
  cursor: pointer;
  flex-shrink: 0;
  max-width: 140px;
  transition: background 0.15s, color 0.15s;
}

.kb-dropdown-root.variant-block .kb-trigger {
  width: 100%;
  max-width: none;
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md, 10px);
  justify-content: space-between;
  background: var(--n-color, var(--sun-black));
  color: var(--sun-text, #ececec);
  font-size: var(--sun-font-base, 14px);
}

.kb-trigger:hover:not(:disabled) {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
  color: var(--sun-text);
}

.kb-trigger:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.kb-leading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.kb-icon,
.kb-chevron {
  flex-shrink: 0;
  color: currentColor;
}

.kb-label {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-chevron {
  opacity: 0.55;
}

.kb-dropdown-root.is-icon-only .kb-label {
  display: none;
}

.kb-dropdown-root.is-icon-only .kb-trigger {
  padding: 0 8px;
  max-width: none;
  gap: 4px;
}

.kb-dropdown-root.is-icon-only .kb-leading {
  gap: 0;
}

.kb-menu {
  padding: 3px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, var(--sun-black));
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
}

.kb-menu-empty {
  padding: 12px 10px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  text-align: center;
}

.kb-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: calc(var(--radius-md, 10px) - 2px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}

.kb-menu-item:hover {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.kb-menu-icon {
  flex-shrink: 0;
  color: var(--sun-text-secondary, #666);
}

.kb-menu-item.is-selected .kb-menu-icon {
  color: var(--sun-text, #ececec);
}

.kb-menu-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kb-menu-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.35;
  color: var(--sun-text, #ececec);
  white-space: nowrap;
}

.kb-menu-tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 500;
  color: var(--sun-text-muted);
}

.kb-menu-desc {
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  color: var(--sun-text-muted, #888);
  white-space: nowrap;
}

.kb-menu-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
}

.kb-menu-check {
  color: var(--sun-text, #ececec);
}

.kb-menu-divider {
  height: 1px;
  margin: 3px 6px;
  background: var(--sun-border);
}

.kb-menu-item--action .kb-menu-title {
  font-size: var(--sun-font-sm, 12px);
}
</style>

<style>
.n-popover.n-popover--raw:has(.kb-menu),
.n-popover-shared:has(.kb-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}

.kb-selector-popover {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}

.kb-selector-popover .n-popover__content,
.kb-selector-popover .v-binder-follower-content,
.v-binder-follower-content:has(.kb-menu) {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
  border-radius: 0 !important;
}
</style>
