<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NEmpty, NIcon, NSpin, NSwitch, NTag } from 'naive-ui'
import { AddOutline } from '@vicons/ionicons5'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'
import { promptKindLabel, shortPromptId, type PromptListItem } from '../../api/prompts'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

function onToggle(item: PromptListItem, enabled: boolean) {
  void page.handleToggleEnabled(item, enabled)
}

function onCreate() {
  if (page.activeTab === 'routing') page.openCreateModal('routing')
  else if (page.activeTab === 'react') page.openCreateModal('react')
}

const showKindTag = () => page.activeTab === 'all'
</script>

<template>
  <aside class="list-panel">
    <div class="panel-head">
      <span class="panel-title">{{ page.listPanelTitle }}</span>
      <NTag :bordered="false" size="tiny" round>{{ page.filteredPrompts.length }}</NTag>
      <NButton
        v-if="page.showListCreateButton"
        size="tiny"
        quaternary
        class="panel-create-btn"
        @click="onCreate"
      >
        <template #icon><NIcon :component="AddOutline" :size="14" /></template>
        {{ page.listCreateButtonLabel }}
      </NButton>
    </div>
    <NSpin :show="page.loading" size="small" class="list-spin">
      <div class="list-body">
        <div v-if="page.filteredPrompts.length" class="prompt-list">
          <button
            v-for="item in page.filteredPrompts"
            :key="item.id"
            type="button"
            class="prompt-row"
            :class="{ active: item.id === page.selectedId }"
            @click="page.selectPrompt(item.id)"
          >
            <div class="prompt-row-head">
              <span class="prompt-name">{{ item.displayName }}</span>
              <NSwitch
                :value="item.enabled"
                size="small"
                @click.stop
                @update:value="(v: boolean) => onToggle(item, v)"
              />
            </div>
            <div class="prompt-row-meta">
              <span class="prompt-id">{{ shortPromptId(item.id) }}</span>
              <NTag
                v-if="showKindTag()"
                size="tiny"
                :bordered="false"
                class="kind-tag"
              >
                {{ promptKindLabel(item.kind) }}
              </NTag>
              <span v-if="item.kind === 'routing-rule'" class="priority-badge">
                P{{ item.priority }}
              </span>
            </div>
          </button>
        </div>
        <div v-else-if="!page.loading" class="empty-wrap">
          <NEmpty size="small" description="暂无提示词" />
        </div>
      </div>
    </NSpin>
  </aside>
</template>

<style scoped>
.list-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-create-btn {
  margin-left: auto;
}

.list-spin {
  flex: 1;
  min-height: 0;
}

.list-spin :deep(.n-spin-content) {
  height: 100%;
}

.list-body {
  padding: 12px 14px 14px;
  min-height: 0;
  overflow: auto;
  height: 100%;
}

.empty-wrap {
  padding: 24px 0;
}

.prompt-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prompt-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.prompt-row:hover {
  border-color: var(--sun-border-light);
}

.prompt-row.active {
  box-shadow: inset 0 0 0 1px var(--sun-accent);
  border-color: var(--sun-accent);
}

.prompt-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.prompt-name {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.prompt-row-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.prompt-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.kind-tag {
  background: transparent !important;
  border: 1px solid var(--sun-border) !important;
  color: var(--sun-text-secondary) !important;
}

.priority-badge {
  font-size: 11px;
  color: var(--sun-text-secondary);
  font-family: var(--sun-font-mono, monospace);
}
</style>
