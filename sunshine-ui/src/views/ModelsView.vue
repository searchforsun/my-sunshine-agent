<script setup lang="ts">
import {
  NButton,
  NCheckbox,
  NDataTable,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NSpin,
  NSwitch,
  NTabPane,
  NTabs,
} from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import { onMounted } from 'vue'
import SidebarToggle from '../components/SidebarToggle.vue'
import { useModelsPage } from '../composables/useModelsPage'

const {
  loading,
  saving,
  activeTab,
  providers,
  definitions,
  scenes,
  sceneKeys,
  routes,
  routeKeys,
  providerOptions,
  modelSelectOptions,
  availableSceneKeyOptions,
  canCreateScene,
  sceneDraftDescription,
  availableRouteKeyOptions,
  canCreateRoute,
  routeDraftDescription,
  providerColumns,
  definitionColumns,
  sceneColumns,
  routeColumns,
  showProviderModal,
  providerEditId,
  providerDraft,
  showDefinitionModal,
  definitionEditId,
  definitionDraft,
  boolParamOptions,
  reasoningSplitSelectValue,
  includeUsageSelectValue,
  thinkingTypeOptions,
  serviceTierOptions,
  showSceneModal,
  sceneEditKey,
  sceneDraft,
  showRouteModal,
  routeEditKey,
  routeDraft,
  showDeleteConfirm,
  deleteTarget,
  refreshPage,
  openCreateProvider,
  openCreateDefinition,
  openCreateScene,
  openCreateRoute,
  submitProvider,
  submitDefinition,
  submitScene,
  submitRoute,
  confirmDelete,
} = useModelsPage()

onMounted(() => {
  void refreshPage()
})
</script>

<template>
  <div class="models-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>模型注册</h2>
      </div>
      <NSpace :size="8">
        <NButton
          v-if="activeTab === 'providers'"
          round
          secondary
          @click="openCreateProvider"
        >
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton
          v-else-if="activeTab === 'models'"
          round
          secondary
          @click="openCreateDefinition"
        >
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton
          v-else-if="activeTab === 'scenes' && canCreateScene"
          round
          secondary
          @click="openCreateScene"
        >
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton
          v-else-if="activeTab === 'routes' && canCreateRoute"
          round
          secondary
          @click="openCreateRoute"
        >
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="loading" @click="refreshPage">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <NTabs v-model:value="activeTab" type="line" :animated="false" class="models-tabs">
      <NTabPane name="providers" tab="供应商" />
      <NTabPane name="models" tab="模型" />
      <NTabPane name="scenes" tab="场景" />
      <NTabPane name="routes" tab="路由策略" />
    </NTabs>

    <div class="models-panel">
      <NSpin :show="loading" class="panel-spin">
        <template v-if="activeTab === 'providers'">
          <NDataTable
            v-if="providers.length"
            :columns="providerColumns"
            :data="providers"
            :bordered="false"
            size="small"
            :row-key="(r) => r.id"
            class="models-table"
          />
          <div v-else-if="!loading" class="panel-empty"><NEmpty description="暂无供应商" /></div>
        </template>
        <template v-else-if="activeTab === 'models'">
          <NDataTable
            v-if="definitions.length"
            :columns="definitionColumns"
            :data="definitions"
            :bordered="false"
            size="small"
            :row-key="(r) => r.id"
            class="models-table"
          />
          <div v-else-if="!loading" class="panel-empty"><NEmpty description="暂无模型" /></div>
        </template>
        <template v-else-if="activeTab === 'scenes'">
          <NDataTable
            v-if="scenes.length"
            :columns="sceneColumns"
            :data="scenes"
            :bordered="false"
            size="small"
            :row-key="(r) => r.id"
            class="models-table"
          />
          <div v-else-if="!loading" class="panel-empty"><NEmpty description="暂无场景绑定" /></div>
        </template>
        <template v-else>
          <NDataTable
            v-if="routes.length"
            :columns="routeColumns"
            :data="routes"
            :bordered="false"
            size="small"
            :row-key="(r) => r.id"
            class="models-table"
          />
          <div v-else-if="!loading" class="panel-empty"><NEmpty description="暂无路由策略" /></div>
        </template>
      </NSpin>
    </div>

    <NModal
      v-model:show="showProviderModal"
      preset="dialog"
      :title="providerEditId == null ? '新建供应商' : '编辑供应商'"
      class="sunshine-dialog"
      style="width: 520px"
    >
      <NForm label-placement="top" :show-feedback="false" class="modal-form">
        <NFormItem label="标识" required>
          <NInput
            v-model:value="providerDraft.providerKey"
            class="sun-field"
            :disabled="providerEditId != null"
            placeholder="deepseek"
          />
        </NFormItem>
        <NFormItem label="显示名" required>
          <NInput v-model:value="providerDraft.displayName" class="sun-field" placeholder="DeepSeek" />
        </NFormItem>
        <NFormItem label="接口地址" required>
          <NInput v-model:value="providerDraft.baseUrl" class="sun-field" placeholder="https://api.deepseek.com" />
        </NFormItem>
        <NFormItem label="路径前缀">
          <NInput v-model:value="providerDraft.pathPrefix" class="sun-field" placeholder="/v1 或空" />
        </NFormItem>
        <NFormItem :label="providerEditId == null ? '密钥' : '密钥（留空保留）'">
          <NInput
            v-model:value="providerDraft.apiKey"
            class="sun-field"
            type="password"
            show-password-on="click"
            placeholder="明文写入"
          />
        </NFormItem>
        <NFormItem label="启用">
          <NSwitch v-model:value="providerDraft.enabled" />
        </NFormItem>
      </NForm>
      <template #action>
        <NSpace>
          <NButton @click="showProviderModal = false">取消</NButton>
          <NButton type="primary" class="action-btn" :loading="saving" @click="submitProvider">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="showDefinitionModal"
      preset="dialog"
      :title="definitionEditId == null ? '新建模型' : '编辑模型'"
      class="sunshine-dialog models-definition-dialog"
      style="width: 820px"
    >
      <NForm label-placement="top" :show-feedback="false" class="modal-form definition-form">
        <div class="form-grid form-grid-3">
          <NFormItem label="供应商" required>
            <NSelect
              v-model:value="definitionDraft.providerKey"
              class="sun-field"
              filterable
              :options="providerOptions"
              :menu-props="{ class: 'models-select-menu' }"
            />
          </NFormItem>
          <NFormItem label="模型名" required>
            <NInput
              v-model:value="definitionDraft.modelName"
              class="sun-field"
              :disabled="definitionEditId != null"
              placeholder="deepseek-v4-pro"
            />
          </NFormItem>
          <NFormItem label="显示名" required>
            <NInput v-model:value="definitionDraft.displayName" class="sun-field" />
          </NFormItem>
        </div>
        <div class="form-grid form-grid-3">
          <NFormItem label="上下文窗口">
            <NInputNumber v-model:value="definitionDraft.contextWindow" class="sun-field" :min="1024" />
          </NFormItem>
          <NFormItem label="输出上限">
            <NInputNumber v-model:value="definitionDraft.maxOutputTokens" class="sun-field" :min="1" />
          </NFormItem>
          <NFormItem label="排序">
            <NInputNumber v-model:value="definitionDraft.sortOrder" class="sun-field" />
          </NFormItem>
        </div>
        <NFormItem label="能力">
          <NSpace>
            <NCheckbox v-model:checked="definitionDraft.capabilities.reasoning">推理</NCheckbox>
            <NCheckbox v-model:checked="definitionDraft.capabilities.multimodal">多模态</NCheckbox>
            <NCheckbox v-model:checked="definitionDraft.capabilities.toolCall">工具调用</NCheckbox>
          </NSpace>
        </NFormItem>
        <div class="form-grid form-grid-3">
          <NFormItem label="reasoning_split">
            <NSelect
              v-model:value="reasoningSplitSelectValue"
              class="sun-field"
              clearable
              placeholder="unset"
              :options="boolParamOptions"
              :menu-props="{ class: 'models-select-menu' }"
            />
          </NFormItem>
          <NFormItem label="thinking.type">
            <NSelect
              v-model:value="definitionDraft.requestExtras.thinking_type"
              class="sun-field"
              clearable
              placeholder="unset"
              :options="thinkingTypeOptions"
              :menu-props="{ class: 'models-select-menu' }"
            />
          </NFormItem>
          <NFormItem label="temperature">
            <NInputNumber
              v-model:value="definitionDraft.requestExtras.temperature"
              class="sun-field"
              clearable
              :min="0"
              :max="2"
              :step="0.1"
              placeholder="unset"
            />
          </NFormItem>
          <NFormItem label="top_p">
            <NInputNumber
              v-model:value="definitionDraft.requestExtras.top_p"
              class="sun-field"
              clearable
              :min="0"
              :max="1"
              :step="0.05"
              placeholder="unset"
            />
          </NFormItem>
          <NFormItem label="stream_options.include_usage">
            <NSelect
              v-model:value="includeUsageSelectValue"
              class="sun-field"
              clearable
              placeholder="unset"
              :options="boolParamOptions"
              :menu-props="{ class: 'models-select-menu' }"
            />
          </NFormItem>
          <NFormItem label="service_tier">
            <NSelect
              v-model:value="definitionDraft.requestExtras.service_tier"
              class="sun-field"
              clearable
              placeholder="unset"
              :options="serviceTierOptions"
              :menu-props="{ class: 'models-select-menu' }"
            />
          </NFormItem>
        </div>
        <div class="form-grid">
          <NFormItem label="用户可选">
            <NSwitch v-model:value="definitionDraft.userSelectable" />
          </NFormItem>
          <NFormItem label="启用">
            <NSwitch v-model:value="definitionDraft.enabled" />
          </NFormItem>
        </div>
      </NForm>
      <template #action>
        <NSpace>
          <NButton @click="showDefinitionModal = false">取消</NButton>
          <NButton type="primary" class="action-btn" :loading="saving" @click="submitDefinition">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="showSceneModal"
      preset="dialog"
      :title="sceneEditKey ? '编辑场景' : '绑定场景'"
      class="sunshine-dialog"
      style="width: 520px"
    >
      <NForm label-placement="top" :show-feedback="false" class="modal-form">
        <NFormItem label="场景" required>
          <NSelect
            v-if="!sceneEditKey"
            v-model:value="sceneDraft.sceneKey"
            class="sun-field"
            :options="availableSceneKeyOptions"
            :menu-props="{ class: 'models-select-menu' }"
          />
          <NInput
            v-else
            :value="sceneDraft.sceneKey"
            class="sun-field"
            disabled
          />
        </NFormItem>
        <NFormItem v-if="sceneDraftDescription" label="场景描述">
          <NInput
            :value="sceneDraftDescription"
            class="sun-field"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            disabled
          />
        </NFormItem>
        <NFormItem label="主模型" required>
          <NSelect
            v-model:value="sceneDraft.primaryModel"
            class="sun-field"
            filterable
            :options="modelSelectOptions"
            :menu-props="{ class: 'models-select-menu' }"
          />
        </NFormItem>
        <NFormItem label="兜底模型">
          <NSelect
            v-model:value="sceneDraft.fallbackModel"
            class="sun-field"
            filterable
            clearable
            :options="modelSelectOptions"
            :menu-props="{ class: 'models-select-menu' }"
          />
        </NFormItem>
        <NFormItem label="扩展参数（JSON）">
          <NInput
            v-model:value="sceneDraft.extrasText"
            class="sun-field prompt-input"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            placeholder='{"temperature":0.2}'
          />
        </NFormItem>
        <NFormItem label="启用">
          <NSwitch v-model:value="sceneDraft.enabled" />
        </NFormItem>
      </NForm>
      <template #action>
        <NSpace>
          <NButton @click="showSceneModal = false">取消</NButton>
          <NButton type="primary" class="action-btn" :loading="saving" @click="submitScene">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="showRouteModal"
      preset="dialog"
      :title="routeEditKey ? '编辑路由策略' : '新建路由策略'"
      class="sunshine-dialog"
      style="width: 560px"
    >
      <NForm label-placement="top" :show-feedback="false" class="modal-form">
        <NFormItem label="调用点" required>
          <NSelect
            v-if="!routeEditKey"
            v-model:value="routeDraft.callSite"
            class="sun-field"
            :options="availableRouteKeyOptions"
            :menu-props="{ class: 'models-select-menu' }"
          />
          <NInput
            v-else
            :value="routeDraft.callSite"
            class="sun-field"
            disabled
          />
        </NFormItem>
        <NFormItem v-if="routeDraftDescription" label="调用点描述">
          <NInput
            :value="routeDraftDescription"
            class="sun-field"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            disabled
          />
        </NFormItem>
        <NFormItem label="候选模型池（按序取首个启用）" required>
          <NSelect
            v-model:value="routeDraft.models"
            class="sun-field"
            multiple
            filterable
            :options="modelSelectOptions"
            :menu-props="{ class: 'models-select-menu' }"
          />
        </NFormItem>
        <NFormItem label="启用">
          <NSwitch v-model:value="routeDraft.enabled" />
        </NFormItem>
        <NFormItem label="备注">
          <NInput v-model:value="routeDraft.remark" class="sun-field" />
        </NFormItem>
      </NForm>
      <template #action>
        <NSpace>
          <NButton @click="showRouteModal = false">取消</NButton>
          <NButton type="primary" class="action-btn" :loading="saving" @click="submitRoute">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="showDeleteConfirm"
      preset="dialog"
      title="确认删除"
      class="sunshine-dialog"
    >
      <p>删除 {{ deleteTarget?.label }}？</p>
      <template #action>
        <NSpace>
          <NButton @click="showDeleteConfirm = false">取消</NButton>
          <NButton type="error" :loading="saving" @click="confirmDelete">删除</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.models-root {
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

.models-tabs {
  flex-shrink: 0;
}

.models-tabs :deep(.n-tabs-nav) {
  padding: 0 2px;
}

.models-panel {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  background: var(--sun-black);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.panel-spin {
  flex: 1;
  min-height: 0;
}

.panel-spin :deep(.n-spin-content) {
  height: 100%;
  overflow: auto;
  padding: 12px 14px 16px;
}

.panel-empty {
  padding: 48px 0;
}

.models-table {
  --n-th-color: var(--sun-black) !important;
  --n-td-color: var(--sun-black) !important;
  --n-th-color-hover: var(--sun-black) !important;
  --n-td-color-hover: var(--sun-row-hover) !important;
  --n-border-color: var(--sun-border) !important;
}

.models-table :deep(.row-actions) {
  display: inline-flex;
  gap: 2px;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-border: none !important;
}

.modal-form {
  display: flex;
  flex-direction: column;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px 16px;
}

.form-grid-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.prompt-input {
  font-family: var(--sun-font-mono, ui-monospace, monospace);
}
</style>

<style>
.models-select-menu {
  background: var(--sun-black) !important;
}
</style>
