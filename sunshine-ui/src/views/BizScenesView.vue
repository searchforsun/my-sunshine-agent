<script setup lang="ts">
import { provide } from 'vue'
import { NButton, NCard, NEmpty, NIcon, NSpace, NSpin, NTab, NTabs, NTag } from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import BizScenesListPanel from '../components/bizscenes/BizScenesListPanel.vue'
import BizSceneDetailPanel from '../components/bizscenes/BizSceneDetailPanel.vue'
import BizSceneFormModals from '../components/bizscenes/BizSceneFormModals.vue'
import { BIZ_SCENES_PAGE_KEY, useBizScenesPage } from '../composables/useBizScenesPage'

const bizScenesPage = useBizScenesPage()
provide(BIZ_SCENES_PAGE_KEY, bizScenesPage)
</script>

<template>
  <div class="biz-scenes-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>业务场景</h2>
      </div>
      <NSpace :size="8">
        <NButton v-if="bizScenesPage.activeTab === 'manual'" round secondary @click="bizScenesPage.showCreate = true">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="bizScenesPage.loading" @click="bizScenesPage.refreshAll">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <div class="tabs-row">
      <NTabs v-model:value="bizScenesPage.activeTab" type="segment" size="small">
        <NTab name="manual" tab="预定义" />
        <NTab name="auto">
          <template #tab>
            <span class="tab-auto">
              自动发现
              <NTag v-if="bizScenesPage.pendingCount" :bordered="false" size="tiny" round type="warning">
                {{ bizScenesPage.pendingCount }} 待审核
              </NTag>
            </span>
          </template>
        </NTab>
      </NTabs>
    </div>

    <div class="biz-layout">
      <BizScenesListPanel />
      <BizSceneDetailPanel v-if="bizScenesPage.selectedScene" :key="bizScenesPage.selectedCode ?? ''" />
      <NCard v-else class="detail-empty" size="small">
        <NSpin :show="bizScenesPage.loading">
          <NEmpty />
        </NSpin>
      </NCard>
    </div>

    <BizSceneFormModals />
  </div>
</template>

<style scoped>
.biz-scenes-root {
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
  gap: 4px;
  min-width: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  line-height: 36px;
  color: var(--sun-text);
}

.tabs-row {
  flex-shrink: 0;
}

.tabs-row :deep(.n-tabs-pane-wrapper) {
  display: none;
}

.tab-auto {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.biz-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-lg) !important;
  border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
  min-height: 0;
  overflow: hidden;
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

@media (max-width: 960px) {
  .biz-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
}
</style>
