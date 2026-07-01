<script setup lang="ts">
defineProps<{
  value: string
  empty?: boolean
}>()
</script>

<template>
  <span class="metric-badge" :class="{ 'metric-badge--empty': empty }">{{ value }}</span>
</template>

<style scoped>
.metric-badge {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  font-size: 11px;
  line-height: 1.35;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--sun-metric-bg);
  color: var(--sun-metric-text);
  font-family: 'JetBrains Mono', monospace;
  white-space: nowrap;
}

.metric-badge--empty {
  color: var(--sun-text-muted);
}
</style>
