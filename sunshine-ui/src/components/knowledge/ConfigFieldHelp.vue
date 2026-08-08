<script setup lang="ts">
import { NTooltip } from 'naive-ui'

defineProps<{
  text: string
}>()
</script>

<template>
  <NTooltip
    trigger="hover"
    placement="top-start"
    :style="{ maxWidth: '320px' }"
    content-class="config-field-help-tooltip"
  >
    <template #trigger>
      <span class="field-help-btn" tabindex="0" role="img" aria-label="配置说明">?</span>
    </template>
    {{ text }}
  </NTooltip>
</template>

<style scoped>
.field-help-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  margin-left: 4px;
  border-radius: 50%;
  background: var(--sun-accent);
  color: var(--btn-primary-text);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  cursor: help;
  vertical-align: middle;
  flex-shrink: 0;
  user-select: none;
}

[data-theme="light"] .field-help-btn {
  background: var(--sun-text);
  color: var(--sun-black);
}

.field-help-btn:hover {
  opacity: 0.88;
}
</style>

<style>
.config-field-help-tooltip {
  max-width: 320px;
  line-height: 1.6;
  font-size: 13px;
  white-space: pre-wrap;
}
</style>
