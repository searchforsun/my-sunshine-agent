<script setup lang="ts">
import WorkflowStudioPropsAside from './WorkflowStudioPropsAside.vue'
import { useWorkflowPropsPanelWidth } from '../../composables/useWorkflowPropsPanelWidth'

defineProps<{
  showExpandBtn?: boolean
}>()

const open = defineModel<boolean>('open', { default: false })

const { panelWidth, resizing, onSplitterPointerDown } = useWorkflowPropsPanelWidth()
</script>

<template>
  <div
    v-if="open"
    class="studio-props-column"
    :class="{ 'is-resizing': resizing }"
    :style="{ width: `${panelWidth}px` }"
  >
    <div
      class="studio-props-splitter"
      title="拖动调节宽度"
      role="separator"
      aria-orientation="vertical"
      aria-label="调节属性面板宽度"
      @pointerdown="onSplitterPointerDown"
    />
    <WorkflowStudioPropsAside v-model:open="open" :show-expand-btn="false" />
  </div>
  <WorkflowStudioPropsAside
    v-else-if="showExpandBtn !== false"
    :open="false"
    :show-expand-btn="true"
    @update:open="open = $event"
  />
</template>

<style scoped>
.studio-props-column {
  position: relative;
  flex-shrink: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.studio-props-splitter {
  position: absolute;
  left: -5px;
  top: 0;
  bottom: 0;
  width: 10px;
  z-index: 5;
  cursor: col-resize;
  touch-action: none;
  user-select: none;
}

.studio-props-splitter::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 2px;
  height: 32px;
  transform: translate(-50%, -50%);
  border-radius: 1px;
  background: var(--sun-text-muted);
  box-shadow: -4px 0 0 var(--sun-text-muted), 4px 0 0 var(--sun-text-muted);
  opacity: 0.35;
  transition: opacity 0.15s, background 0.15s, box-shadow 0.15s;
  pointer-events: none;
}

.studio-props-column:hover .studio-props-splitter::after,
.studio-props-column.is-resizing .studio-props-splitter::after {
  opacity: 0.7;
}

.studio-props-column.is-resizing .studio-props-splitter::after {
  background: var(--sun-blue, #58a6ff);
  box-shadow: -4px 0 0 var(--sun-blue, #58a6ff), 4px 0 0 var(--sun-blue, #58a6ff);
}

@media (max-width: 960px) {
  .studio-props-splitter {
    display: none;
  }

  .studio-props-column {
    width: 100% !important;
    border-left: none;
    border-top: 1px solid var(--sun-border);
  }
}
</style>

<style>
body.wf-props-resizing {
  cursor: col-resize !important;
  user-select: none !important;
}
</style>
