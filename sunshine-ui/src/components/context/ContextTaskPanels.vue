<script setup lang="ts">
import { computed, inject } from 'vue'
import { NEmpty, NIcon, NTag } from 'naive-ui'
import { FolderOpenOutline } from '@vicons/ionicons5'
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
  <div v-else class="task-context-body">
    <section class="w0-panel">
      <header class="panel-head">
        <span class="panel-title">
          <NTag :bordered="false" size="tiny" class="kind-chip">W0</NTag>
          工作区
        </span>
      </header>
      <div v-if="hasWorkspace" class="w0-fields">
        <div class="w0-field">
          <span class="field-key">workspaceId</span>
          <span class="field-value">{{ conv.workspaceId || '—' }}</span>
        </div>
        <div class="w0-field">
          <span class="field-key">checkoutPath</span>
          <span class="field-value mono">{{ conv.checkoutPath || '—' }}</span>
        </div>
      </div>
      <div v-else class="w0-empty">
        <NIcon :component="FolderOpenOutline" :size="16" />
        <span>未绑定工作区</span>
      </div>
    </section>

    <section class="carrier-panel">
      <header class="panel-head">
        <span class="panel-title">任务上下文载体</span>
      </header>
      <div class="carrier-tags">
        <NTag :bordered="false" size="small" class="kind-chip">T0 任务进度</NTag>
        <NTag :bordered="false" size="small" class="kind-chip">H1 计划笔记本</NTag>
        <NTag :bordered="false" size="small" class="kind-chip">task-L3 检索</NTag>
      </div>
      <p class="carrier-note">只读展示；写入与闸门由 task-scene 运行时管理</p>
    </section>
  </div>
</template>

<style scoped>
.task-context-body {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: auto;
}

.w0-panel,
.carrier-panel {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.kind-chip {
  --n-color: var(--sun-accent-soft, rgba(122, 162, 247, 0.12)) !important;
  --n-text-color: var(--sun-text-secondary) !important;
}

.w0-fields {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.w0-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
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

.w0-empty {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.carrier-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.carrier-note {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}
</style>
