<script setup lang="ts">
import { NTabPane, NTabs } from 'naive-ui'
import SidebarToggle from '../SidebarToggle.vue'
import TenantSelector from './TenantSelector.vue'
import KbSelector from './KbSelector.vue'
import KbDocList from './KbDocList.vue'
import KbDocPanel from './KbDocPanel.vue'
import KbDebugPanel from './KbDebugPanel.vue'
import type { KbDocument, KnowledgeBase } from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'

defineProps<{
  tenantId: TenantId
  kbs: KnowledgeBase[]
  documents: KbDocument[]
  selectedKbId: string | null
  selectedDocId: string | null
  loadingKbs: boolean
  loadingDocs: boolean
}>()

const emit = defineEmits<{
  'update:tenantId': [value: TenantId]
  'update:selectedKbId': [value: string]
  'select-doc': [docId: string]
  createKb: []
  docIngested: []
}>()
</script>

<template>
  <div class="kb-layout">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>知识库工作台</h2>
      </div>
      <div class="page-header-context">
        <span class="context-label">租户</span>
        <TenantSelector
          :model-value="tenantId"
          @update:model-value="emit('update:tenantId', $event)"
        />
        <span class="context-sep" aria-hidden="true" />
        <span class="context-label">知识库</span>
        <KbSelector
          :kbs="kbs"
          :model-value="selectedKbId"
          :loading="loadingKbs"
          @update:model-value="emit('update:selectedKbId', $event)"
          @create="emit('createKb')"
        />
      </div>
    </header>

    <main class="workbench-panel">
      <NTabs
        type="line"
        :animated="false"
        pane-wrapper-class="kb-tab-pane-wrapper"
        class="workbench-tabs"
      >
        <NTabPane name="docs" tab="文档管理" display-directive="show">
          <div class="docs-workspace">
            <KbDocList
              :kb-id="selectedKbId"
              :documents="documents"
              :selected-doc-id="selectedDocId"
              :loading="loadingDocs"
              @select-doc="emit('select-doc', $event)"
            />
            <KbDocPanel
              :tenant-id="tenantId"
              :kb-id="selectedKbId"
              :doc-id="selectedDocId"
              @ingested="emit('docIngested')"
            />
          </div>
        </NTabPane>
        <NTabPane name="debug" tab="检索调试" display-directive="show">
          <KbDebugPanel :tenant-id="tenantId" :kb-id="selectedKbId" />
        </NTabPane>
      </NTabs>
    </main>
  </div>
</template>

<style scoped>
.kb-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 12px;
  box-sizing: border-box;
  overflow: hidden;
  background: var(--sun-black);
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

.page-header-context {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.context-label {
  font-size: 12px;
  color: var(--sun-text-muted);
  white-space: nowrap;
}

.context-sep {
  width: 1px;
  height: 16px;
  background: var(--sun-border);
  margin: 0 2px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  line-height: 36px;
  color: var(--sun-text);
}

.workbench-panel {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  background: var(--sun-black);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.workbench-tabs {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.workbench-tabs :deep(.n-tabs) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.workbench-tabs :deep(.n-tabs-nav) {
  flex-shrink: 0;
  padding: 0 16px;
  background: transparent;
}

.workbench-tabs :deep(.kb-tab-pane-wrapper) {
  flex: 1;
  min-height: 0 !important;
  height: auto !important;
  max-height: none !important;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.workbench-tabs :deep(.n-tab-pane) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 16px;
}

.docs-workspace {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(240px, 280px) minmax(0, 1fr);
  gap: 12px;
  overflow: hidden;
}

@media (max-width: 960px) {
  .page-header {
    flex-wrap: wrap;
    align-items: flex-start;
  }

  .page-header-context {
    width: 100%;
    justify-content: flex-end;
  }

  .docs-workspace {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(160px, 0.38fr) minmax(220px, 0.62fr);
  }
}
</style>
