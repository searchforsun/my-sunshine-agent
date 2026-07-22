<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NEmpty, NIcon, NInput, NSpin, NSwitch, NTag } from 'naive-ui'
import { AddOutline, FlashOutline, BookOutline, SearchOutline } from '@vicons/ionicons5'
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

const showKindTag = () => page.activeTab === 'system'
</script>

<template>
  <aside class="list-panel">
    <div class="panel-head">
      <span class="panel-title">{{ page.listPanelTitle }}</span>
      <NTag :bordered="false" size="tiny" round>{{ page.filteredPrompts.length }}</NTag>
      <div class="panel-head-actions">
        <NButton
          v-if="page.activeTab === 'system'"
          size="tiny"
          quaternary
          class="panel-action-btn"
          :class="{ active: page.systemPane === 'principles' }"
          @click="page.systemPane === 'principles' ? page.closePrinciples() : page.openPrinciples()"
        >
          <template #icon><NIcon :component="BookOutline" :size="14" /></template>
          {{ page.systemPane === 'principles' ? '返回编辑' : '原理分析' }}
        </NButton>
        <NButton
          v-if="page.activeTab === 'routing'"
          size="tiny"
          quaternary
          class="panel-action-btn"
          :class="{ active: page.routingPane === 'dry-run' }"
          @click="page.openRoutingDryRun()"
        >
          <template #icon><NIcon :component="FlashOutline" :size="14" /></template>
          试跑
        </NButton>
        <NButton
          v-if="page.showListCreateButton"
          size="tiny"
          quaternary
          class="panel-action-btn"
          @click="onCreate"
        >
          <template #icon><NIcon :component="AddOutline" :size="14" /></template>
          {{ page.listCreateButtonLabel }}
        </NButton>
      </div>
    </div>
    <div class="list-search">
      <NInput
        v-model:value="page.promptSearch"
        placeholder="搜索名称或 ID…"
        size="small"
        round
        clearable
        class="search-input"
        :disabled="page.loading"
      >
        <template #prefix>
          <NIcon :component="SearchOutline" :size="14" />
        </template>
      </NInput>
    </div>
    <NSpin :show="page.loading" size="small" class="list-spin">
      <div class="list-body">
        <div v-if="page.filteredPrompts.length" class="prompt-list">
          <button
            v-for="item in page.filteredPrompts"
            :key="item.id"
            type="button"
            class="prompt-row"
            :class="{
              active: page.systemPane !== 'principles'
                && page.routingPane !== 'dry-run'
                && item.id === page.selectedId,
            }"
            @click="page.selectPrompt(item.id)"
          >
            <div class="prompt-row-head">
              <span class="prompt-name">{{ item.displayName }}</span>
              <div class="prompt-row-head-right">
                <span
                  v-if="item.kind === 'routing-rule'"
                  class="meta-chip priority-chip"
                  :title="`优先级 ${item.priority}（越大越优先）`"
                >
                  P{{ item.priority }}
                </span>
                <NSwitch
                  :value="item.enabled"
                  size="small"
                  @click.stop
                  @update:value="(v: boolean) => onToggle(item, v)"
                />
              </div>
            </div>
            <div class="prompt-row-meta">
              <span class="prompt-id">{{ shortPromptId(item.id) }}</span>
              <NTag
                v-if="showKindTag()"
                size="tiny"
                :bordered="false"
                class="meta-chip"
              >
                {{ promptKindLabel(item.kind) }}
              </NTag>
            </div>
          </button>
        </div>
        <div v-else-if="!page.loading" class="empty-wrap">
          <NEmpty
            size="small"
            :description="page.promptSearch.trim() ? '无匹配项' : '暂无提示词'"
          />
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
  padding: 14px 16px 0;
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-head-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 2px;
}

.panel-action-btn.active {
  color: var(--sun-accent) !important;
  font-weight: 600;
}

.list-search {
  padding: 10px 12px;
  flex-shrink: 0;
}

.search-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
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
  font-weight: 600;
  border-color: var(--sun-text);
}

.prompt-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.prompt-row-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.prompt-name {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
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

.meta-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 1px 7px;
  border-radius: 4px;
  border: none !important;
  background: color-mix(in srgb, var(--sun-text) 8%, transparent) !important;
  color: var(--sun-text-secondary) !important;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.4;
  --n-color: color-mix(in srgb, var(--sun-text) 8%, transparent) !important;
  --n-text-color: var(--sun-text-secondary) !important;
  --n-border: none !important;
}

.priority-chip {
  min-width: 36px;
  font-weight: 600;
  font-family: var(--sun-font-mono, monospace);
  letter-spacing: 0.02em;
}
</style>
