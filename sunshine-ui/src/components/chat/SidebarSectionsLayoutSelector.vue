<script setup lang="ts">
import { NTabs, NTabPane } from 'naive-ui'
import {
  SIDEBAR_SECTIONS_LAYOUT_OPTIONS,
  type SidebarSectionsLayout,
} from '../../api/sidebarSectionsLayouts'

defineProps<{
  modelValue: SidebarSectionsLayout
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: SidebarSectionsLayout]
}>()

function onUpdate(value: string) {
  if (value === 'vertical' || value === 'horizontal') {
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
    class="sidebar-layout-tabs"
    :class="{ 'is-disabled': disabled }"
    @update:value="onUpdate"
  >
    <NTabPane
      v-for="opt in SIDEBAR_SECTIONS_LAYOUT_OPTIONS"
      :key="opt.value"
      :name="opt.value"
      :tab="opt.label"
      :disabled="disabled"
    />
  </NTabs>
</template>

<style scoped>
.sidebar-layout-tabs {
  width: 100%;
}

.sidebar-layout-tabs.is-disabled {
  opacity: 0.6;
  pointer-events: none;
}

.sidebar-layout-tabs :deep(.n-tabs-pane-wrapper) {
  display: none;
}
</style>
