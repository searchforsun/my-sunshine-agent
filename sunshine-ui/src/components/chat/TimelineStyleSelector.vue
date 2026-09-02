<script setup lang="ts">
import { NTabs, NTabPane } from 'naive-ui'
import type { TimelineStyle } from '../../composables/useTimelineStyle'

defineProps<{
  modelValue: TimelineStyle
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: TimelineStyle]
}>()

function onUpdate(value: string) {
  if (value === 'minimal' || value === 'standard') {
    emit('update:modelValue', value)
  }
}
</script>

<template>
  <NTabs
    :value="modelValue"
    type="segment"
    size="small"
    :animated="false"
    class="timeline-style-tabs"
    :class="{ 'is-disabled': disabled }"
    @update:value="onUpdate"
  >
    <NTabPane name="minimal" tab="极简" :disabled="disabled" />
    <NTabPane name="standard" tab="标准" :disabled="disabled" />
  </NTabs>
</template>

<style scoped>
.timeline-style-tabs {
  width: 100%;
}

.timeline-style-tabs.is-disabled {
  opacity: 0.6;
  pointer-events: none;
}

.timeline-style-tabs :deep(.n-tabs-pane-wrapper) {
  display: none;
}
</style>
