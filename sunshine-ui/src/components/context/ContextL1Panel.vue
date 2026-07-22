<script setup lang="ts">
import { inject } from 'vue'
import { NEmpty, NSpin } from 'naive-ui'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi
</script>

<template>
  <template v-if="!page.selectedConv">
    <div class="empty-wrap fill">
      <NEmpty size="small" description="暂无会话" />
    </div>
  </template>
  <NSpin v-else :show="page.loadingL1" class="tab-spin">
    <div v-if="page.l1Snapshot" class="l1-body">
      <div v-if="page.l1Rows.length" class="l1-row-list">
        <article
          v-for="(row, i) in page.l1Rows"
          :key="page.l1RowKey(row, i)"
          class="l1-row"
          :class="{ expanded: page.expandedL1Key === page.l1RowKey(row, i) }"
          :data-band="row.band"
          role="button"
          tabindex="0"
          @click="page.toggleL1Expand(page.l1RowKey(row, i))"
          @keydown.enter.prevent="page.toggleL1Expand(page.l1RowKey(row, i))"
          @keydown.space.prevent="page.toggleL1Expand(page.l1RowKey(row, i))"
        >
          <header class="l1-row-head">
            <span class="l1-band-tag" :data-band="row.band">{{ page.rowTag(row) }}</span>
            <span
              v-if="row.band === 'mid' && row.assistantSummarized"
              class="l1-band-tag soft"
              data-band="mid"
            >摘要</span>
            <span class="l1-row-time">{{ page.formatTime(row.at || undefined) }}</span>
          </header>
          <div class="l1-row-scroll">
            <template v-if="row.band === 'far'">
              <div class="l1-role-block">
                <span class="l1-role">摘要</span>
                <div class="l1-role-text">{{ row.assistantText || '（空）' }}</div>
              </div>
            </template>
            <template v-else>
              <div class="l1-role-block">
                <span class="l1-role">User</span>
                <div class="l1-role-text">{{ row.userText || '（空）' }}</div>
              </div>
              <div class="l1-role-block">
                <span class="l1-role">Assistant</span>
                <div class="l1-role-text">{{ row.assistantText || '（空）' }}</div>
              </div>
            </template>
          </div>
        </article>
      </div>
      <div v-else class="empty-wrap fill">
        <NEmpty size="small" description="暂无会话" />
      </div>
    </div>
    <div v-else class="empty-wrap fill">
      <NEmpty size="small" description="暂无会话" />
    </div>
  </NSpin>
</template>

<style scoped>
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

.l1-body {
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

.l1-band-tag[data-band='mid'] {
  color: #6a6b55;
  background: color-mix(in srgb, #9aa06e 18%, transparent);
  border-color: color-mix(in srgb, #9aa06e 35%, transparent);
}

.l1-band-tag[data-band='far'] {
  color: #6b5d6e;
  background: color-mix(in srgb, #9a849e 18%, transparent);
  border-color: color-mix(in srgb, #9a849e 35%, transparent);
}

.l1-band-tag.soft {
  font-weight: 500;
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

.l1-role-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.l1-role {
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text-muted);
}

.l1-role-text {
  font-size: 13px;
  line-height: 1.55;
  color: var(--sun-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.l1-empty-hint {
  margin: 0;
  font-size: 13px;
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

.empty-wrap.fill {
  min-height: 0;
  height: 100%;
  align-self: stretch;
}
</style>
