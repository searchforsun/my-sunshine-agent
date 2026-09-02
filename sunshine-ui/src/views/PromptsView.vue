<script setup lang="ts">
import { onMounted, provide } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NSpace,
  NTabPane,
  NTabs,
} from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import PromptsListPanel from '../components/prompts/PromptsListPanel.vue'
import PromptDetailPanel from '../components/prompts/PromptDetailPanel.vue'
import PromptPrinciplesPanel from '../components/prompts/PromptPrinciplesPanel.vue'
import RoutingRuleEditor from '../components/prompts/RoutingRuleEditor.vue'
import RoutingDryRunPanel from '../components/prompts/RoutingDryRunPanel.vue'
import { PROMPTS_PAGE_KEY, usePromptsPage } from '../composables/usePromptsPage'

const page = usePromptsPage()
provide(PROMPTS_PAGE_KEY, page)

onMounted(() => {
  void page.refreshList()
})
</script>

<template>
  <div class="prompts-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>提示词</h2>
      </div>
      <NSpace :size="8">
        <NButton
          round
          type="primary"
          class="action-btn"
          :loading="page.loading"
          @click="page.refreshList()"
        >
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <NTabs v-model:value="page.activeTab" type="line" :animated="false" class="prompts-tabs">
      <NTabPane name="system" tab="系统配置" />
      <NTabPane name="routing" tab="路由规则" />
    </NTabs>

    <div v-if="page.activeTab === 'system'" class="prompts-layout">
      <PromptsListPanel />
      <PromptPrinciplesPanel v-if="page.systemPane === 'principles'" />
      <PromptDetailPanel v-else />
    </div>

    <div v-else-if="page.activeTab === 'routing'" class="prompts-layout">
      <PromptsListPanel />
      <RoutingDryRunPanel v-if="page.routingPane === 'dry-run'" />
      <RoutingRuleEditor v-else />
    </div>

    <NModal
      v-model:show="page.showCreateModal"
      preset="dialog"
      :title="page.createModalTitle"
      class="sunshine-dialog"
    >
      <NForm class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem label="ID" required>
          <NInput
            v-model:value="page.createDraft.id"
            class="sun-field"
            :placeholder="page.createIdPlaceholder"
          />
        </NFormItem>
        <NFormItem label="展示名" required>
          <NInput
            v-model:value="page.createDraft.displayName"
            class="sun-field"
            placeholder="我的规则"
          />
        </NFormItem>
        <NFormItem label="描述">
          <NInput
            v-model:value="page.createDraft.description"
            class="sun-field"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
          />
        </NFormItem>
        <NFormItem label="优先级">
          <NInputNumber
            v-model:value="page.createDraft.priority"
            class="sun-field"
            :min="0"
            :show-button="false"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="page.showCreateModal = false">取消</NButton>
        <NButton
          type="primary"
          class="action-btn"
          :loading="page.creating"
          @click="page.handleCreate()"
        >
          创建
        </NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.prompts-root {
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

.prompts-tabs {
  flex-shrink: 0;
}

.prompts-tabs :deep(.n-tabs-nav) {
  padding: 0 2px;
}

.prompts-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
}

.modal-form :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
}
</style>
