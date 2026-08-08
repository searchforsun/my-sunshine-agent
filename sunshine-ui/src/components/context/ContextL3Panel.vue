<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NEmpty, NSpin } from 'naive-ui'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi
</script>

<template>
  <div class="ops-bar">
    <NButton size="small" secondary :loading="page.runningGc" @click="page.handleGc">
      清理过期索引
    </NButton>
    <NButton
      size="small"
      type="primary"
      class="action-btn"
      :loading="page.reingesting"
      :disabled="!page.selectedConvId"
      @click="page.handleReingest"
    >
      重新会话索引
    </NButton>
  </div>
  <template v-if="!page.selectedConv">
    <div class="empty-wrap fill">
      <NEmpty size="small" description="请选择会话" />
    </div>
  </template>
  <NSpin v-else :show="page.loadingL3" class="tab-spin">
    <div v-if="page.l3Entries.length" class="l3-body">
      <div class="l1-row-list">
        <article
          v-for="(entry, i) in page.l3Entries"
          :key="page.l3RowKey(entry, i)"
          class="l1-row l3-row"
          :class="{ expanded: page.expandedL3Key === page.l3RowKey(entry, i) }"
          role="button"
          tabindex="0"
          @click="page.toggleL3Expand(page.l3RowKey(entry, i))"
          @keydown.enter.prevent="page.toggleL3Expand(page.l3RowKey(entry, i))"
          @keydown.space.prevent="page.toggleL3Expand(page.l3RowKey(entry, i))"
        >
          <header class="l1-row-head">
            <span class="l1-band-tag" data-band="near">{{ page.l3RoleLabel(entry.role) }}</span>
            <span class="l1-band-tag soft" data-band="mid">#{{ entry.chunkIndex }}</span>
            <span class="l1-row-time">生成 {{ page.formatTime(entry.createdAt) }}</span>
            <span class="l1-row-time">
              过期 {{ entry.expiresAt ? page.formatTime(entry.expiresAt) : '无硬过期' }}
            </span>
          </header>
          <div class="l1-row-scroll">
            <div class="l1-role-text">{{ entry.content || '（空）' }}</div>
          </div>
        </article>
      </div>
    </div>
    <div v-else class="empty-wrap fill">
      <NEmpty
        size="small"
        description="该会话尚无 L3 索引（可点「重新会话索引」）"
      />
    </div>
  </NSpin>
</template>

<style scoped>
.ops-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
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

.l3-row .l1-row-head {
  flex-wrap: wrap;
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
[data-theme="dark"] .l1-band-tag[data-band='near'] {
  color: #9bb5cc;
  background: color-mix(in srgb, #7a8fa3 28%, var(--sun-black));
  border-color: color-mix(in srgb, #7a8fa3 45%, var(--sun-black));
}

.l1-band-tag[data-band='mid'] {
  color: #6a6b55;
  background: color-mix(in srgb, #9aa06e 18%, transparent);
  border-color: color-mix(in srgb, #9aa06e 35%, transparent);
}
[data-theme="dark"] .l1-band-tag[data-band='mid'] {
  color: #b8bf85;
  background: color-mix(in srgb, #9aa06e 28%, var(--sun-black));
  border-color: color-mix(in srgb, #9aa06e 45%, var(--sun-black));
}

.l1-band-tag[data-band='far'] {
  color: #6b5d6e;
  background: color-mix(in srgb, #9a849e 18%, transparent);
  border-color: color-mix(in srgb, #9a849e 35%, transparent);
}
[data-theme="dark"] .l1-band-tag[data-band='far'] {
  color: #b7a6bb;
  background: color-mix(in srgb, #9a849e 28%, var(--sun-black));
  border-color: color-mix(in srgb, #9a849e 45%, var(--sun-black));
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
