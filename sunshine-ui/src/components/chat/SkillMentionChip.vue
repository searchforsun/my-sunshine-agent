<script setup lang="ts">
defineProps<{
  token: string
  displayName?: string
}>()
</script>

<template>
  <span class="skill-mention-chip" :title="displayName || token">
    <span class="skill-mention-chip__at">@</span><span class="skill-mention-chip__label">{{ token }}</span>
  </span>
</template>
