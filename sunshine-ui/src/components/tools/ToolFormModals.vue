<script setup lang="ts">
import { inject } from 'vue'
import {
  NButton,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NTabPane,
  NTabs,
} from 'naive-ui'
import { TOOLS_PAGE_KEY, type ToolsPageApi } from '../../composables/useToolsPage'

const page = inject(TOOLS_PAGE_KEY) as ToolsPageApi
</script>

<template>
  <NModal
    v-model:show="page.showToolEditModal"
    preset="dialog"
    title="编辑工具配置"
    class="sunshine-dialog tool-desc-dialog"
    style="width: 720px; max-width: 94vw;"
  >
    <div class="tool-desc-meta">
      <div class="tool-desc-name">{{ page.editingTool?.displayName }}</div>
      <div class="tool-desc-id">{{ page.editingTool?.id }}</div>
    </div>
    <NForm
      :ref="page.bindToolConfigFormRef"
      :model="page.toolConfigModel"
      :rules="page.toolConfigRules"
      class="modal-form tool-config-form"
      label-placement="top"
      :show-feedback="true"
    >
      <NFormItem label="描述" path="description" required>
        <NInput
          v-model:value="page.toolConfigModel.description"
          class="sun-field tool-desc-input"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 8 }"
          placeholder="工具用途说明，供 Agent 选择工具时参考"
        />
      </NFormItem>
      <NFormItem label="时间线摘要模板" path="timelineSummaryTemplate">
        <NInput
          v-model:value="page.toolConfigModel.timelineSummaryTemplate"
          class="sun-field"
          placeholder="如 {count} 条财务消息；留空则使用默认"
        />
      </NFormItem>
      <NFormItem label="摘要占位符提取（JSON）" path="timelineSummaryExtract">
        <NInput
          v-model:value="page.toolConfigModel.timelineSummaryExtract"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 8 }"
          placeholder='如 {"count":"regex:共\\s*(\\d+)\\s*条"}'
        />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="page.showToolEditModal = false">取消</NButton>
      <NButton type="primary" class="action-btn" :loading="page.saving" @click="page.handleSaveToolConfig">保存</NButton>
    </template>
  </NModal>

  <NModal
    v-model:show="page.showToolSchemaModal"
    preset="dialog"
    title="Schema 预览"
    class="sunshine-dialog tool-schema-dialog"
    style="width: 800px; max-width: 94vw;"
  >
    <div class="tool-desc-meta">
      <div class="tool-desc-name">{{ page.schemaViewTool?.displayName }}</div>
      <div class="tool-desc-id">{{ page.schemaViewTool?.id }}</div>
    </div>
    <pre class="schema-preview tool-schema-preview">{{ JSON.stringify(page.schemaViewTool?.parameters ?? {}, null, 2) }}</pre>
    <template #action>
      <NButton type="primary" class="action-btn" @click="page.showToolSchemaModal = false">关闭</NButton>
    </template>
  </NModal>

  <NModal
    v-model:show="page.showMcpCreateModal"
    preset="dialog"
    title="新建 MCP 服务"
    class="sunshine-dialog mcp-dialog"
    style="width: 640px; max-width: 92vw;"
  >
    <NTabs v-model:value="page.mcpCreateMode" type="segment" size="small" class="mcp-modal-tabs">
      <NTabPane name="form" tab="表单" />
      <NTabPane name="json" tab="mcp.json" />
    </NTabs>
    <NForm v-if="page.mcpCreateMode === 'form'" class="modal-form" label-placement="top" :show-feedback="false">
      <NFormItem label="服务 ID" required>
        <NInput v-model:value="page.mcpCreateDraft.id" class="sun-field" placeholder="my-mcp-server" />
      </NFormItem>
      <NFormItem label="展示名">
        <NInput v-model:value="page.mcpCreateDraft.displayName" class="sun-field" placeholder="可选" />
      </NFormItem>
      <NFormItem label="Transport" required>
        <NSelect
          v-model:value="page.mcpCreateDraft.transport"
          class="sun-field"
          :options="page.transportOptions"
        />
      </NFormItem>
      <NFormItem v-if="page.mcpCreateDraft.transport === 'stdio'" label="Command" required>
        <NSelect
          v-model:value="page.mcpCreateDraft.command"
          class="sun-field"
          filterable
          tag
          :options="page.commandOptions"
        />
      </NFormItem>
      <NFormItem v-if="page.mcpCreateDraft.transport === 'stdio'" label="Args（JSON 数组）">
        <NInput
          v-model:value="page.mcpCreateDraft.argsJson"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          placeholder='["-y", "@modelcontextprotocol/server-filesystem", "/data"]'
        />
      </NFormItem>
      <NFormItem v-if="page.mcpCreateDraft.transport === 'stdio'" label="Env（JSON 对象，可选）">
        <NInput
          v-model:value="page.mcpCreateDraft.envJson"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder='{"KEY": "value"}'
        />
      </NFormItem>
      <NFormItem v-else label="Endpoint" required>
        <NInput v-model:value="page.mcpCreateDraft.endpoint" class="sun-field" placeholder="http://..." />
      </NFormItem>
    </NForm>
    <NForm v-else class="modal-form" label-placement="top" :show-feedback="false">
      <NFormItem label="粘贴 Cursor 兼容 mcp.json">
        <NInput
          v-model:value="page.mcpJsonDraft"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 10, maxRows: 18 }"
          placeholder="mcp.json 内容"
        />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="page.showMcpCreateModal = false">取消</NButton>
      <NButton
        type="primary"
        class="action-btn"
        :loading="page.saving"
        :disabled="page.mcpCreateMode === 'form' ? !page.canCreateMcpForm : !page.canCreateMcpJson"
        @click="page.handleCreateMcp"
      >
        {{ page.mcpCreateMode === 'json' ? '导入' : '创建' }}
      </NButton>
    </template>
  </NModal>

  <NModal
    v-model:show="page.showMcpDeleteConfirm"
    preset="dialog"
    title="删除 MCP 服务"
    class="sunshine-dialog"
  >
    <p>确定删除「{{ page.selectedMcp?.displayName || page.selectedMcp?.id }}」？关联工具将从 Catalog 移除。</p>
    <template #action>
      <NButton @click="page.showMcpDeleteConfirm = false">取消</NButton>
      <NButton type="error" :loading="page.saving" @click="page.handleDeleteMcp">删除</NButton>
    </template>
  </NModal>

  <input
    :ref="page.bindMcpImportInputRef"
    type="file"
    accept=".json,application/json"
    class="hidden-file"
    @change="page.handleImportMcp"
  />
</template>

<style scoped>
.mcp-modal-tabs {
  margin-bottom: 12px;
}

.mcp-modal-tabs :deep(.n-tabs-pane-wrapper) {
  display: none;
}

.modal-form :deep(.n-input) {
  --n-color: var(--sun-black) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-box-shadow-focus: none !important;
}

.modal-form :deep(.n-select) {
  --n-color: var(--sun-black) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-box-shadow-focus: none !important;
}

.schema-preview.tool-schema-preview {
  max-height: min(560px, 62vh);
}

:global(.sunshine-dialog.tool-schema-dialog.n-dialog) {
  max-width: 800px;
  width: min(800px, 94vw);
}

:global(.sunshine-dialog.tool-schema-dialog .n-dialog__content) {
  white-space: normal;
}

.schema-preview {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  font-size: 12px;
  font-family: var(--sun-font-mono, monospace);
  overflow: auto;
  max-height: 240px;
}

.tool-desc-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.tool-desc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.tool-desc-id {
  font-size: 12px;
  font-family: var(--sun-font-mono, monospace);
  color: var(--sun-text-muted);
  word-break: break-all;
}

.tool-desc-input {
  width: 100%;
  --n-border-radius: var(--radius-md) !important;
}

.tool-desc-input :deep(.n-input__textarea-el) {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.6;
  padding: 12px 14px;
  min-height: 220px;
}

:global(.sunshine-dialog.tool-desc-dialog.n-dialog) {
  max-width: 720px;
  width: min(720px, 94vw);
}

:global(.sunshine-dialog.tool-desc-dialog .n-dialog__content) {
  white-space: normal;
}

.tool-config-form :deep(.n-form-item) {
  margin-bottom: 12px;
}

.hidden-file {
  display: none;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-border: none !important;
}
</style>
