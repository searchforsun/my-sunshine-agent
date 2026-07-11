<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NDataTable, NEmpty, NIcon, NSpin, NTag } from 'naive-ui'
import { SyncOutline } from '@vicons/ionicons5'
import { TOOLS_PAGE_KEY, type ToolsPageApi } from '../../composables/useToolsPage'

const page = inject(TOOLS_PAGE_KEY) as ToolsPageApi
</script>

<template>
  <div class="tools-layout">
    <aside class="list-panel">
      <div class="panel-head">
        <span class="panel-title">应用</span>
        <NTag :bordered="false" size="tiny" round>{{ page.sdkApps.length }}</NTag>
      </div>
      <NSpin :show="page.loading" size="small" class="list-spin">
        <div class="list-body">
          <div v-if="page.sdkApps.length" class="item-list">
            <button
              v-for="app in page.sdkApps"
              :key="app.id"
              type="button"
              class="item-row"
              :class="{ active: app.id === page.selectedSdkId }"
              @click="page.selectedSdkId = app.id"
            >
              <div class="item-row-head">
                <span class="item-name">{{ app.displayName || app.id }}</span>
                <NTag :type="page.statusTagType(app.status)" size="tiny" round :bordered="false">
                  {{ app.status }}
                </NTag>
              </div>
              <span class="item-id">{{ app.id }}</span>
            </button>
          </div>
          <div v-else-if="!page.loading" class="empty-wrap">
            <NEmpty size="small" description="暂无 SDK 应用" />
          </div>
        </div>
      </NSpin>
    </aside>

    <main v-if="page.selectedSdk" class="detail-panel">
      <div class="detail-toolbar">
        <div class="detail-toolbar-text">
          <h3 class="detail-heading">{{ page.selectedSdk.displayName || page.selectedSdk.id }}</h3>
          <span class="detail-id">{{ page.selectedSdk.nacosService }} · schema v{{ page.selectedSdk.schemaVersion }}</span>
        </div>
        <NButton
          size="small"
          round
          type="primary"
          class="action-btn"
          :loading="page.syncing"
          @click="page.handleSyncSdk"
        >
          <template #icon><NIcon :component="SyncOutline" /></template>
          同步 Catalog
        </NButton>
      </div>
      <div class="detail-scroll">
        <section class="form-section">
          <header class="form-section-head">
            <h4 class="form-section-title">工具列表</h4>
            <NTag :bordered="false" size="tiny" round>{{ page.sdkTools.length }}</NTag>
          </header>
          <NDataTable
            v-if="page.sdkTools.length"
            :columns="page.toolColumns"
            :data="page.sdkTools"
            :bordered="false"
            size="small"
            class="tools-table"
          />
          <NEmpty v-else size="small" description="暂无工具，请先同步" />
        </section>
      </div>
    </main>
    <main v-else class="detail-panel detail-empty">
      <NEmpty description="选择左侧 SDK 应用" />
    </main>
  </div>
</template>

<style scoped>
.tools-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.list-panel,
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.list-panel {
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
}

.empty-wrap {
  padding: 24px 0;
}

.detail-panel {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.detail-toolbar-text {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.detail-heading {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.form-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.item-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  text-align: left;
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.item-row:hover {
  border-color: var(--sun-border-light);
}

.item-row.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.item-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.item-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tools-table :deep(.n-data-table) {
  --n-th-color: var(--sun-black);
  --n-td-color: var(--sun-black);
  --n-border-color: var(--sun-border);
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-border: none !important;
}

:deep(.tool-timeline-cell) {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

:deep(.tool-timeline-template) {
  display: block;
  font-family: inherit;
  font-size: var(--sun-font-sm, 12px);
  line-height: 1.45;
  word-break: break-word;
  white-space: pre-wrap;
  color: var(--sun-text);
  background: transparent;
  padding: 0;
}

:deep(.tool-timeline-extract-tip) {
  margin: 0;
  max-width: 420px;
  max-height: 240px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 960px) {
  .tools-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
}
</style>
