<script setup lang="ts">
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'

defineProps<{
  title: string
  help?: string
}>()
</script>

<template>
  <section class="wf-sec">
    <header class="wf-sec-head">
      <span class="wf-sec-title">{{ title }}</span>
      <ConfigFieldHelp v-if="help" :text="help" />
    </header>
    <div class="wf-sec-body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.wf-sec {
  padding: 14px 14px 16px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}
.wf-sec-head {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-bottom: 10px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--sun-border);
}
.wf-sec-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  letter-spacing: -0.01em;
}
.wf-sec-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.wf-sec-body :deep(.n-form-item) {
  margin-bottom: 0;
}
.wf-sec-body :deep(.n-form-item-label) {
  padding-bottom: 6px;
  font-size: 12px;
  color: var(--sun-text-secondary);
}
.wf-sec-body :deep(.wf-out-row),
.wf-sec-body :deep(.wf-out-extract),
.wf-sec-body :deep(.wf-ref-list),
.wf-sec-body :deep(.wf-out-note),
.wf-sec-body :deep(.execution-policy) {
  margin: 0;
}
</style>
