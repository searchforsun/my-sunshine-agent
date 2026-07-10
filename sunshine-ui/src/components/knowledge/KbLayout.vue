<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NTabPane, NTabs } from 'naive-ui'
import SidebarToggle from '../SidebarToggle.vue'
import TenantSelector from './TenantSelector.vue'
import KbSelector from './KbSelector.vue'
import KbDocList from './KbDocList.vue'
import KbDocPanel from './KbDocPanel.vue'
import KbDebugPanel from './KbDebugPanel.vue'
import KbConfigPanel from './KbConfigPanel.vue'
import KbEvalPanel from './KbEvalPanel.vue'
import KbAppliedConfigSelector from './KbAppliedConfigSelector.vue'
import type { KbDocument, KnowledgeBase } from '../../api/ragAdmin'
import type { TenantId } from '../../api/tenants'
import { useKbWorkbenchContext } from '../../composables/useKbWorkbenchContext'
import {
  useKbWorkbenchRouteState,
  type KbWorkbenchTab,
} from '../../composables/useKbWorkbenchRouteState'

defineProps<{
  tenantId: TenantId
  kbs: KnowledgeBase[]
  documents: KbDocument[]
  selectedKbId: string | null
  selectedDocId: string | null
  loadingKbs: boolean
  loadingDocs: boolean
}>()

const { revision } = useKbWorkbenchContext()
const route = useRoute()
const routeState = useKbWorkbenchRouteState()
const activeTab = ref<KbWorkbenchTab>(routeState.readTab())

watch(activeTab, (tab) => {
  routeState.syncQuery({ tab })
})

watch(
  () => route.query.tab,
  () => {
    const tab = routeState.readTab()
    if (activeTab.value !== tab) activeTab.value = tab
  },
)

const emit = defineEmits<{
  'update:tenantId': [value: TenantId]
  'update:selectedKbId': [value: string]
  'select-doc': [docId: string]
  createKb: []
  createDoc: []
  docIngested: []
  refreshDocuments: []
  docDeleted: []
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
          variant="block"
          @update:model-value="emit('update:selectedKbId', $event)"
          @create="emit('createKb')"
        />
        <template v-if="selectedKbId">
          <span class="context-sep" aria-hidden="true" />
          <span class="context-label">应用配置</span>
          <KbAppliedConfigSelector :tenant-id="tenantId" :kb-id="selectedKbId" />
        </template>
      </div>
    </header>

    <NTabs
      v-model:value="activeTab"
      type="line"
      :animated="false"
      class="workbench-tabs"
    >
      <NTabPane name="docs" tab="文档管理" />
      <NTabPane name="debug" tab="检索调试" />
      <NTabPane name="config" tab="参数配置" />
      <NTabPane name="eval" tab="评测" />
    </NTabs>

    <div v-if="activeTab === 'docs'" class="workbench-body docs-workspace">
      <KbDocList
        :kb-id="selectedKbId"
        :documents="documents"
        :selected-doc-id="selectedDocId"
        :loading="loadingDocs"
        @select-doc="emit('select-doc', $event)"
        @create-doc="emit('createDoc')"
      />
      <KbDocPanel
        :key="`doc-${revision}`"
        :tenant-id="tenantId"
        :kb-id="selectedKbId"
        :doc-id="selectedDocId"
        @refreshed="emit('refreshDocuments')"
        @deleted="emit('docDeleted')"
      />
    </div>
    <div v-else-if="activeTab === 'debug'" class="workbench-body">
      <KbDebugPanel :key="`debug-${revision}`" :tenant-id="tenantId" :kb-id="selectedKbId" />
    </div>
    <div v-else-if="activeTab === 'config'" class="workbench-body">
      <KbConfigPanel :key="`config-${revision}`" :tenant-id="tenantId" :kb-id="selectedKbId" />
    </div>
    <div v-else-if="activeTab === 'eval'" class="workbench-body">
      <KbEvalPanel
        :key="`eval-${revision}`"
        :tenant-id="tenantId"
        :kb-id="selectedKbId"
        :kb-display-name="kbs.find((k) => k.kbId === selectedKbId)?.displayName"
        :documents="documents"
        :loading-docs="loadingDocs"
        @refresh-documents="emit('refreshDocuments')"
      />
    </div>
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

.workbench-tabs {
  flex-shrink: 0;
}

.workbench-tabs :deep(.n-tabs-nav) {
  padding: 0 2px;
}

.workbench-tabs :deep(.n-tabs-pane-wrapper) {
  display: none !important;
}

.workbench-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.docs-workspace {
  display: grid;
  grid-template-columns: minmax(240px, 280px) minmax(0, 1fr);
  gap: 16px;
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
