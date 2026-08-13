<script setup lang="ts">
import { NEmpty } from 'naive-ui'

defineProps<{ text: string }>()
</script>

<template>
  <div class="empty-wrap fill">
    <NEmpty size="small" :description="text" />
  </div>
</template>

<style scoped>
.empty-wrap.fill {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  align-self: stretch;
}
</style>
