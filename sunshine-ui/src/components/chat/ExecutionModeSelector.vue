<script setup lang="ts">
import { computed } from 'vue'
import { NDropdown } from 'naive-ui'
import {
  EXECUTION_MODE_OPTIONS,
  findExecutionModeOption,
  type ExecutionPreference,
} from '../../api/executionModes'

const props = defineProps<{
  modelValue: ExecutionPreference
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: ExecutionPreference]
}>()

const current = computed(() => findExecutionModeOption(props.modelValue))

const menuOptions = computed(() =>
  EXECUTION_MODE_OPTIONS.map(opt => ({
    key: opt.value,
    label: opt.label,
  })),
)

function onSelect(key: string | number) {
  emit('update:modelValue', String(key) as ExecutionPreference)
}
</script>

<template>
  <NDropdown
    trigger="click"
    placement="top-start"
    :options="menuOptions"
    :disabled="disabled"
    @select="onSelect"
  >
    <button
      type="button"
      class="mode-selector"
      :disabled="disabled"
      :title="current.description"
    >
      <svg class="mode-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span class="mode-label">{{ current.label }}</span>
      <svg class="mode-chevron" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" aria-hidden="true">
        <polyline points="6 9 12 15 18 9" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </button>
  </NDropdown>
</template>

<style scoped>
.mode-selector {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--sun-border);
  border-radius: 999px;
  background: color-mix(in srgb, var(--sun-bg) 90%, var(--sun-text-muted));
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm);
  cursor: pointer;
  flex-shrink: 0;
  max-width: 100%;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.mode-selector:hover:not(:disabled) {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

.mode-selector:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.mode-icon {
  flex-shrink: 0;
  opacity: 0.85;
}

.mode-label {
  font-weight: 500;
  white-space: nowrap;
}

.mode-chevron {
  flex-shrink: 0;
  opacity: 0.55;
}
</style>
