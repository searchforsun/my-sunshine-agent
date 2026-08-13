<script setup lang="ts">
import { provide } from 'vue'
import {
  NButton,
  NIcon,
  NSelect,
  NSpace,
  NTabPane,
  NTabs,
} from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import TenantSelector from '../components/knowledge/TenantSelector.vue'
import ContextConversationList from '../components/context/ContextConversationList.vue'
import ContextL1Panel from '../components/context/ContextL1Panel.vue'
import ContextL2Panel from '../components/context/ContextL2Panel.vue'
import ContextL3Panel from '../components/context/ContextL3Panel.vue'
import ContextTaskW0Panel from '../components/context/ContextTaskW0Panel.vue'
import ContextTaskEmptyPanel from '../components/context/ContextTaskEmptyPanel.vue'
import { CONTEXT_PAGE_KEY, useContextPage } from '../composables/useContextPage'
import '../utils/stream-markdown/styles.css'

const contextPage = useContextPage()
provide(CONTEXT_PAGE_KEY, contextPage)
</script>

<template>
  <div class="context-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>上下文</h2>
      </div>
      <NSpace :size="8" align="center" class="page-header-actions">
        <span class="context-label">租户</span>
        <TenantSelector v-model:model-value="contextPage.filterTenantId" />
        <span class="context-sep" aria-hidden="true" />
        <span class="context-label">用户</span>
        <NSelect
          v-model:value="contextPage.filterUserId"
          class="sun-field filter-select"
          :options="contextPage.userOptions"
          :render-label="contextPage.userSelectRenderLabel"
          :loading="contextPage.loadingUsers"
          :consistent-menu-width="false"
          filterable
          clearable
          placeholder="选择用户"
          size="small"
        />
        <NButton
          round
          type="primary"
          class="action-btn"
          :loading="contextPage.refreshing"
          @click="contextPage.refreshAll"
        >
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <div class="context-tabs">
      <NTabs
        v-model:value="contextPage.kindTab"
        type="line"
        @update:value="(v: string) => (contextPage.kindTab = v as 'chat' | 'task')"
      >
        <NTabPane name="chat" tab="对话" />
        <NTabPane name="task" tab="任务" />
      </NTabs>
    </div>

    <div class="context-layout">
      <ContextConversationList />

      <main class="detail-panel">
        <template v-if="contextPage.kindTab === 'chat'">
          <NTabs v-model:value="contextPage.activeTab" type="line" size="small" class="layer-tabs">
            <NTabPane name="l1" tab="L1 会话快照">
              <ContextL1Panel />
            </NTabPane>

            <NTabPane name="l2" tab="L2 用户状态">
              <ContextL2Panel />
            </NTabPane>

            <NTabPane name="l3" tab="L3 历史索引">
              <ContextL3Panel />
            </NTabPane>
          </NTabs>
        </template>

        <NTabs v-else v-model:value="contextPage.taskTab" type="line" size="small" class="layer-tabs">
          <NTabPane name="w0" tab="W0 工作区">
            <ContextTaskW0Panel />
          </NTabPane>

          <NTabPane name="t0" tab="T0 任务进度">
            <ContextTaskEmptyPanel text="暂无任务进度" />
          </NTabPane>

          <NTabPane name="h1" tab="H1 计划笔记本">
            <ContextTaskEmptyPanel text="暂无计划笔记本" />
          </NTabPane>

          <NTabPane name="l3" tab="L3 任务检索">
            <ContextTaskEmptyPanel text="暂无任务检索" />
          </NTabPane>
        </NTabs>
      </main>
    </div>
  </div>
</template>

<style scoped>
.context-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
  gap: 12px;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--sun-text);
  line-height: 1.2;
}

.select-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.filter-select {
  width: 220px;
}

.context-label {
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  flex-shrink: 0;
}

.context-sep {
  width: 1px;
  height: 16px;
  background: var(--sun-border);
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

.context-tabs {
  flex-shrink: 0;
  border-bottom: 1px solid var(--sun-border);
}

.context-tabs :deep(.n-tabs-nav) {
  padding: 0;
}

.context-tabs :deep(.n-tabs-nav-scroll-content) {
  justify-content: flex-start;
}

.context-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 12px;
}

.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.layer-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 14px 14px;
}

.layer-tabs :deep(.n-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.layer-tabs :deep(.n-tabs-nav) {
  padding-top: 6px;
  flex-shrink: 0;
}

.layer-tabs :deep(.n-tabs-tab) {
  font-size: 13px;
  padding: 8px 0;
}

.layer-tabs :deep(.n-tabs-bar) {
  height: 2px;
}

.layer-tabs :deep(.n-tabs-pane-wrapper),
.layer-tabs :deep(.n-tabs-content),
.layer-tabs :deep(.n-tab-pane) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

:deep(.sun-field .n-input),
:deep(.sun-field .n-input-wrapper),
:deep(.sun-field .n-base-selection),
:deep(.sun-field .n-input-number) {
  background: var(--sun-black) !important;
}

@media (max-width: 960px) {
  .context-layout {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(200px, 40%) 1fr;
  }
}
</style>
