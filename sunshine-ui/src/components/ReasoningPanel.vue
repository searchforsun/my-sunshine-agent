<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  text: string
  expanded: boolean
  live?: boolean
}>()

const emit = defineEmits<{
  toggle: []
}>()

const preview = computed(() => {
  const t = props.text.trim()
  if (!t) return ''
  const oneLine = t.replace(/\s+/g, ' ')
  return oneLine.length > 72 ? `${oneLine.slice(0, 72)}…` : oneLine
})
</script>

<template>
  <div class="chat-meta-panel reasoning-panel" :class="{ 'is-expanded': expanded, 'is-live': live }">
    <button type="button" class="chat-meta-toggle reasoning-toggle" @click="emit('toggle')">
      <svg
        class="chat-meta-chevron"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
      >
        <polyline points="9 18 15 12 9 6" />
      </svg>
      <span class="chat-meta-title">思考过程</span>
      <span v-if="live" class="chat-meta-badge">思考中</span>
      <span v-if="!expanded && preview" class="chat-meta-preview">{{ preview }}</span>
    </button>
    <div v-show="expanded" class="chat-meta-body">
      <pre class="reasoning-body">{{ text }}</pre>
    </div>
  </div>
</template>

<style scoped>
.reasoning-body {
  margin: 0;
  padding: 0;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: var(--sun-font-base);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text-muted);
}
</style>
