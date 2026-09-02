<script setup lang="ts">
import { inject } from 'vue'
import { NEmpty, NIcon, NInput, NSpin } from 'naive-ui'
import { SearchOutline, TimeOutline } from '@vicons/ionicons5'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi
</script>

<template>
  <aside class="list-panel">
    <div class="panel-head">
      <span class="panel-title">{{ page.kindTab === 'task' ? '任务会话' : '对话会话' }}</span>
      <NTag :bordered="false" size="tiny" round>{{ page.filteredConversations.length }}</NTag>
    </div>
    <div class="list-search">
      <NInput
        v-model:value="page.convSearch"
        placeholder="搜索标题或 ID…"
        size="small"
        round
        clearable
        class="search-input"
        :disabled="page.loadingConvs"
      >
        <template #prefix>
          <NIcon :component="SearchOutline" :size="14" />
        </template>
      </NInput>
    </div>
    <NSpin :show="page.loadingConvs" class="list-spin">
      <div v-if="page.filteredConversations.length" class="entry-list">
        <button
          v-for="item in page.filteredConversations"
          :key="item.id"
          type="button"
          class="conv-row"
          :class="{ active: item.id === page.selectedConvId }"
          :title="item.id"
          @click="page.selectConversation(item.id)"
        >
          <span class="conv-title">
            {{ item.title || '新对话' }}
          </span>
          <span class="conv-time">
            <NIcon :component="TimeOutline" :size="12" />
            {{ page.formatTime(item.updatedAt) }}
          </span>
        </button>
      </div>
      <div v-else class="empty-wrap">
        <NEmpty
          size="small"
          :description="page.conversations.length && page.convSearch.trim() ? '无匹配会话' : '该用户暂无会话'"
        />
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
  justify-content: space-between;
  gap: 8px;
  padding: 14px 14px 0;
  flex-shrink: 0;
}

.panel-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
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
  display: flex;
  flex-direction: column;
  overflow: auto;
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
}

.conv-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-align: left;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--sun-text);
  padding: 10px 12px;
  cursor: pointer;
}

.conv-row:hover {
  border-color: var(--sun-border-strong, var(--sun-text-muted));
}

.conv-row.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.conv-title {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--sun-text-muted);
}

.empty-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  width: 100%;
}
</style>
