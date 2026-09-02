<script setup lang="ts">
import { provide } from 'vue'
import { NButton, NIcon, NSpace, NTabPane, NTabs } from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import ToolsetTabPanel from '../components/tools/ToolsetTabPanel.vue'
import SdkToolsPanel from '../components/tools/SdkToolsPanel.vue'
import McpToolsPanel from '../components/tools/McpToolsPanel.vue'
import ToolFormModals from '../components/tools/ToolFormModals.vue'
import { TOOLS_PAGE_KEY, useToolsPage } from '../composables/useToolsPage'

const toolsPage = useToolsPage()
provide(TOOLS_PAGE_KEY, toolsPage)
</script>

<template>
  <div class="tools-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>工具管理</h2>
      </div>
      <NSpace :size="8">
        <NButton round type="primary" class="action-btn" :loading="toolsPage.loading" @click="toolsPage.refreshCurrentTab">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <NTabs v-model:value="toolsPage.activeTab" type="line" :animated="false" class="tools-tabs">
      <NTabPane name="sdk" tab="SDK 应用" />
      <NTabPane name="mcp" tab="MCP 服务" />
      <NTabPane name="toolset" tab="工具集配置" />
    </NTabs>

    <SdkToolsPanel v-if="toolsPage.activeTab === 'sdk'" />
    <McpToolsPanel v-else-if="toolsPage.activeTab === 'mcp'" />

    <div v-else-if="toolsPage.activeTab === 'toolset'" class="tools-layout toolset-layout">
      <ToolsetTabPanel />
    </div>

    <ToolFormModals />
  </div>
</template>

<style scoped>
.tools-root {
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
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--sun-text);
}

.tools-tabs {
  flex-shrink: 0;
}

.tools-tabs :deep(.n-tabs-nav) {
  padding: 0 2px;
}

.tools-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.toolset-layout {
  grid-template-columns: 1fr;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-border: none !important;
}

@media (max-width: 960px) {
  .tools-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
}
</style>
