<script setup lang="ts">
import { computed, ref } from 'vue'
import { NIcon, NPopover, NTooltip } from 'naive-ui'
import { CheckmarkOutline, ChevronDownOutline, CubeOutline } from '@vicons/ionicons5'
import type { ModelCapabilities } from '../../api/models'

export interface ChatModelOption {
  label: string
  value: string
  providerKey?: string
  capabilities: ModelCapabilities
  disabled?: boolean
  disabledReason?: string
}

const props = defineProps<{
  modelValue: string | null
  options: ChatModelOption[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
}>()

const showMenu = ref(false)

const current = computed(() => {
  if (!props.modelValue) {
    return { label: '默认', value: null as string | null }
  }
  const hit = props.options.find((o) => o.value === props.modelValue)
  return hit
    ? { label: hit.label, value: hit.value }
    : { label: props.modelValue, value: props.modelValue }
})

function select(value: string | null, disabled?: boolean) {
  if (disabled) return
  emit('update:modelValue', value)
  showMenu.value = false
}

function onShowUpdate(next: boolean) {
  if (props.disabled) return
  showMenu.value = next
}
</script>

<template>
  <div class="model-dropdown-root">
    <NPopover
      :show="showMenu"
      trigger="click"
      content-class="chat-model-popover"
      placement="top-end"
      :width="280"
      :disabled="disabled"
      raw
      :show-arrow="false"
      @update:show="onShowUpdate"
    >
      <template #trigger>
        <button
          type="button"
          class="model-selector"
          :disabled="disabled"
          :title="current.label"
        >
          <NIcon class="model-icon" :component="CubeOutline" :size="14" />
          <span class="model-label">{{ current.label }}</span>
          <NIcon class="model-chevron" :component="ChevronDownOutline" :size="12" />
        </button>
      </template>

      <div class="model-menu" role="listbox" aria-label="模型">
        <button
          type="button"
          role="option"
          class="model-menu-item"
          :class="{ 'is-selected': modelValue == null }"
          :aria-selected="modelValue == null"
          @click="select(null)"
        >
          <span class="model-menu-title">默认</span>
          <span class="model-menu-check-slot" aria-hidden="true">
            <NIcon
              v-if="modelValue == null"
              class="model-menu-check"
              :component="CheckmarkOutline"
              :size="18"
            />
          </span>
        </button>
        <template v-for="opt in options" :key="opt.value">
          <NTooltip v-if="opt.disabled" placement="left" :delay="200">
            <template #trigger>
              <button
                type="button"
                role="option"
                class="model-menu-item is-disabled"
                :aria-selected="modelValue === opt.value"
                disabled
              >
                <span class="model-menu-title">{{ opt.label }}</span>
              </button>
            </template>
            {{ opt.disabledReason || '当前消息含图片，该模型不支持多模态' }}
          </NTooltip>
          <button
            v-else
            type="button"
            role="option"
            class="model-menu-item"
            :class="{ 'is-selected': modelValue === opt.value }"
            :aria-selected="modelValue === opt.value"
            @click="select(opt.value)"
          >
            <span class="model-menu-title">{{ opt.label }}</span>
            <span class="model-menu-check-slot" aria-hidden="true">
              <NIcon
                v-if="modelValue === opt.value"
                class="model-menu-check"
                :component="CheckmarkOutline"
                :size="18"
              />
            </span>
          </button>
        </template>
      </div>
    </NPopover>
  </div>
</template>

<style scoped>
.model-dropdown-root {
  display: inline-flex;
  max-width: 100%;
}

.model-selector {
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
  max-width: 180px;
  transition: color 0.15s, background 0.15s;
}

.model-selector:hover:not(:disabled) {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
  color: var(--sun-text, #ececec);
}

.model-selector:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.model-icon {
  flex-shrink: 0;
  opacity: 0.9;
}

.model-label {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.model-chevron {
  flex-shrink: 0;
  opacity: 0.55;
}

.model-menu {
  padding: 3px;
  border-radius: var(--radius-lg, 12px);
  background: var(--n-color, var(--sun-black));
  box-shadow: var(--shadow-elevated, 0 4px 12px rgba(0, 0, 0, 0.12));
  border: 1px solid var(--sun-border, #e8e8e8);
  overflow: hidden;
}

.model-menu-item {
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

.model-menu-item:hover:not(:disabled) {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.model-menu-item.is-disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.model-menu-title {
  flex: 1;
  min-width: 0;
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.35;
  color: var(--sun-text, #ececec);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.model-menu-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  width: 20px;
  justify-content: center;
}

.model-menu-check {
  color: var(--sun-text, #ececec);
}
</style>

<style>
.n-popover.n-popover--raw:has(.model-menu),
.n-popover-shared:has(.model-menu) {
  box-shadow: none !important;
  background: transparent !important;
  border-radius: 0 !important;
  padding: 0 !important;
}

.chat-model-popover {
  padding: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
}
</style>
