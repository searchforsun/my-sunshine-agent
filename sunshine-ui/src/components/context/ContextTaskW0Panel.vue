<script setup lang="ts">
import { computed, inject } from 'vue'
import { NEmpty } from 'naive-ui'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi

const conv = computed(() => page.selectedConv)
const hasWorkspace = computed(() =>
  Boolean(conv.value?.workspaceId || conv.value?.checkoutPath),
)
</script>

<template>
  <div v-if="!conv" class="empty-wrap fill">
    <NEmpty size="small" description="暂无会话" />
  </div>
  <div v-else-if="hasWorkspace" class="w0-fields">
    <div class="w0-field">
      <span class="field-key">workspaceId</span>
      <span class="field-value">{{ conv.workspaceId }}</span>
    </div>
    <div class="w0-field">
      <span class="field-key">checkoutPath</span>
      <span class="field-value mono">{{ conv.checkoutPath || '—' }}</span>
    </div>
  </div>
  <div v-else class="empty-wrap fill">
    <NEmpty size="small" description="未绑定工作区" />
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

.w0-fields {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 14px;
}

.w0-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-key {
  font-size: 11px;
  color: var(--sun-text-muted);
}

.field-value {
  font-size: 13px;
  color: var(--sun-text);
  word-break: break-all;
}

.field-value.mono {
  font-family: var(--sun-font-mono, ui-monospace, 'JetBrains Mono', monospace);
}
</style>
