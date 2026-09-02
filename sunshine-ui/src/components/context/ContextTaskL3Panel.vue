<script setup lang="ts">
import { inject, reactive } from 'vue'
import { NButton, NEmpty, NInput, NSpin, useMessage } from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi
const message = useMessage()
const local = reactive({ query: '' })

async function doSearch() {
  page.taskHistoryQuery = local.query
  if (!page.selectedConvId) {
    message.warning('请先选择任务会话')
    return
  }
  await page.runTaskHistorySearch()
}

function formatScore(score: number): string {
  if (score == null) return '—'
  return score.toFixed(3)
}

function timeLabel(ms: number): string {
  if (!ms) return '—'
  return new Date(ms).toLocaleString()
}
</script>

<template>
  <div class="l3-search-bar">
    <NInput
      v-model:value="local.query"
      placeholder="输入关键词检索任务历史正文 / 过程"
      size="small"
      clearable
      @keydown.enter.prevent="doSearch"
    />
    <NButton
      size="small"
      type="primary"
      class="action-btn"
      :loading="page.loadingTaskHistory"
      :disabled="!page.selectedConvId"
      @click="doSearch"
    >
      <template #icon><SearchOutline /></template>
      检索
    </NButton>
  </div>

  <template v-if="!page.selectedConv">
    <div class="empty-wrap fill">
      <NEmpty size="small" description="请先选择任务会话" />
    </div>
  </template>
  <NSpin v-else :show="page.loadingTaskHistory" class="tab-spin">
    <div v-if="page.taskHistoryHits.length" class="l3-body">
      <div class="l1-row-list">
        <article
          v-for="(hit, i) in page.taskHistoryHits"
          :key="page.taskHistoryRowKey(hit, i)"
          class="l1-row"
          :class="{ expanded: page.expandedTaskHistoryKey === page.taskHistoryRowKey(hit, i) }"
          role="button"
          tabindex="0"
          @click="page.toggleTaskHistoryExpand(page.taskHistoryRowKey(hit, i))"
          @keydown.enter.prevent="page.toggleTaskHistoryExpand(page.taskHistoryRowKey(hit, i))"
          @keydown.space.prevent="page.toggleTaskHistoryExpand(page.taskHistoryRowKey(hit, i))"
        >
          <header class="l1-row-head">
            <span class="l1-band-tag" data-band="near">{{ formatScore(hit.score) }}</span>
            <span class="l1-row-time">{{ timeLabel(hit.createdAt) }}</span>
            <span class="l1-row-time">会话 {{ hit.convId }}</span>
          </header>
          <div class="l1-row-scroll">
            <div class="l1-role-text">{{ hit.content || '（空）' }}</div>
          </div>
        </article>
      </div>
    </div>
    <div v-else class="empty-wrap fill">
      <NEmpty size="small" description="输入关键词检索任务历史" />
    </div>
  </NSpin>
</template>

<style scoped>
.l3-search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}

.tab-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}

.tab-spin :deep(.n-spin-container),
.tab-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.l3-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 0 8px;
}

.l1-row-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.l1-row {
  height: 220px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  background: var(--sun-black);
  cursor: pointer;
  transition: height 0.18s ease, border-color 0.15s ease;
}

.l1-row:hover {
  border-color: var(--sun-text-muted);
}

.l1-row.expanded {
  height: 480px;
}

.l1-row-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.l1-band-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid transparent;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}

.l1-band-tag[data-band='near'] {
  color: #5b6b7a;
  background: color-mix(in srgb, #7a8fa3 18%, transparent);
  border-color: color-mix(in srgb, #7a8fa3 35%, transparent);
}

[data-theme='dark'] .l1-band-tag[data-band='near'] {
  color: #9bb5cc;
  background: color-mix(in srgb, #7a8fa3 28%, var(--sun-black));
  border-color: color-mix(in srgb, #7a8fa3 45%, var(--sun-black));
}

.l1-row-time {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.l1-row-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.l1-role-text {
  font-size: 13px;
  line-height: 1.55;
  color: var(--sun-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.empty-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  width: 100%;
}

.empty-wrap.fill {
  min-height: 0;
  height: 100%;
  align-self: stretch;
}
</style>
