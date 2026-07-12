<script setup lang="ts">
import { provide } from 'vue'
import { NButton, NCard, NEmpty, NIcon, NSpace, NSpin } from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import WorkflowsListPanel from '../components/workflows/WorkflowsListPanel.vue'
import WorkflowDetailPanel from '../components/workflows/WorkflowDetailPanel.vue'
import WorkflowFormModals from '../components/workflows/WorkflowFormModals.vue'
import { WORKFLOWS_PAGE_KEY, useWorkflowsPage } from '../composables/useWorkflowsPage'

const workflowsPage = useWorkflowsPage()
provide(WORKFLOWS_PAGE_KEY, workflowsPage)
</script>

<template>
  <div class="workflows-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>工作流</h2>
      </div>
      <NSpace :size="8">
        <NButton round secondary @click="workflowsPage.showCreate = true">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="workflowsPage.loading" @click="void workflowsPage.refreshPage()">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <div class="workflows-layout">
      <WorkflowsListPanel />
      <WorkflowDetailPanel v-if="workflowsPage.selectedWorkflow" :key="workflowsPage.selectedId ?? ''" />
      <NCard v-else class="detail-empty" size="small">
        <NSpin :show="workflowsPage.loading">
          <NEmpty description="选择左侧工作流进行编辑" />
        </NSpin>
      </NCard>
    </div>

    <WorkflowFormModals />
  </div>
</template>

<style scoped>
.workflows-root {
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
  gap: 16px;
  flex-shrink: 0;
  min-height: 36px;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-header-main h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.workflows-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) minmax(0, 1fr);
  gap: 12px;
  overflow: hidden;
}

.workflows-layout > * {
  min-height: 0;
}

.detail-empty {
  min-height: 0;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
}

.detail-empty :deep(.n-card__content) {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}

@media (max-width: 900px) {
  .workflows-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
}
</style>
