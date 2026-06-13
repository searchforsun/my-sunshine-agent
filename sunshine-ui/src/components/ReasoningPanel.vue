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
  <div class="reasoning-panel" :class="{ 'is-expanded': expanded, 'is-live': live }">
    <button type="button" class="reasoning-toggle" @click="emit('toggle')">
      <svg
        class="reasoning-chevron"
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
      <span class="reasoning-title">思考过程</span>
      <span v-if="live" class="reasoning-badge">思考中</span>
      <span v-if="!expanded && preview" class="reasoning-preview">{{ preview }}</span>
    </button>
    <div v-show="expanded" class="reasoning-body-wrap">
      <pre class="reasoning-body">{{ text }}</pre>
    </div>
  </div>
</template>

<style scoped>
.reasoning-panel {
  position: relative;
  z-index: 2;
  margin-bottom: 10px;
  border: 1px solid var(--sun-border, #263348);
  border-radius: 10px;
  background: rgba(245, 158, 11, 0.06);
  overflow: hidden;
}

.reasoning-panel.is-live {
  border-color: rgba(245, 158, 11, 0.35);
  box-shadow: 0 0 0 1px rgba(245, 158, 11, 0.08);
}

.reasoning-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: var(--sun-text-secondary, #94a3b8);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}

.reasoning-toggle:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--sun-text, #e2e8f0);
}

.reasoning-chevron {
  flex-shrink: 0;
  transition: transform 0.2s ease;
  color: var(--sun-amber, #f59e0b);
}

.is-expanded .reasoning-chevron {
  transform: rotate(90deg);
}

.reasoning-title {
  font-weight: 600;
  color: var(--sun-amber-light, #fbbf24);
  flex-shrink: 0;
}

.reasoning-badge {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  background: rgba(245, 158, 11, 0.15);
  color: var(--sun-amber-light, #fbbf24);
}

.reasoning-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--sun-text-muted, #64748b);
}

.reasoning-body-wrap {
  border-top: 1px solid rgba(245, 158, 11, 0.12);
  max-height: 280px;
  overflow: auto;
}

.reasoning-body {
  margin: 0;
  padding: 10px 14px 12px;
  font-family: 'JetBrains Mono', ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--sun-text-muted, #64748b);
}
</style>
