<script setup lang="ts">
import MentionChip from './MentionChip.vue'

defineProps<{
  token: string
  displayName?: string
}>()
</script>

<template>
  <MentionChip kind="skill" :token="token" :display-name="displayName" />
</template>
