<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import {
  NButton,
  NDataTable,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NSelect,
  NSpace,
  NSwitch,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
} from 'naive-ui'
import { AddOutline, EllipsisHorizontal, SearchOutline, SyncOutline } from '@vicons/ionicons5'
import { formatSkillVersionTime } from '../../utils/formatSkillVersionTime'
import { TOOLS_PAGE_KEY, type ToolsPageApi } from '../../composables/useToolsPage'

const page = inject(TOOLS_PAGE_KEY) as ToolsPageApi
const mcpSearch = ref('')
const filteredMcpServers = computed(() => {
  const q = mcpSearch.value.trim().toLowerCase()
  if (!q) return page.mcpServers
  return page.mcpServers.filter(
    s =>
      s.id.toLowerCase().includes(q)
      || (s.displayName ?? '').toLowerCase().includes(q),
  )
})
</script>

<template>
  <div class="tools-layout">
    <aside class="list-panel">
      <div class="panel-head">
        <span class="panel-title">服务</span>
        <NTag :bordered="false" size="tiny" round>{{ filteredMcpServers.length }}</NTag>
        <NButton
          size="tiny"
          quaternary
          class="panel-create-btn"
          @click="page.openMcpCreate"
        >
          <template #icon><NIcon :component="AddOutline" :size="14" /></template>
          新建 MCP
        </NButton>
      </div>
      <div class="list-search">
        <NInput
          v-model:value="mcpSearch"
          placeholder="搜索名称或 ID…"
          size="small"
          round
          clearable
          class="search-input"
          :disabled="page.loading"
        >
          <template #prefix>
            <NIcon :component="SearchOutline" :size="14" />
          </template>
        </NInput>
      </div>
      <NSpin :show="page.loading" size="small" class="list-spin">
        <div class="list-body">
          <div v-if="filteredMcpServers.length" class="item-list">
            <div
              v-for="server in filteredMcpServers"
              :key="server.id"
              class="item-row-wrap"
            >
              <button
                type="button"
                class="item-row"
                :class="{ active: server.id === page.selectedMcpId }"
                @click="page.selectedMcpId = server.id"
              >
                <div class="item-row-head">
                  <span class="item-name">{{ server.displayName || server.id }}</span>
                  <NSwitch
                    size="small"
                    :value="server.enabled"
                    @click.stop
                    @update:value="(v: boolean) => page.handleToggleMcpServer(server, v)"
                  />
                </div>
                <div class="item-row-foot">
                  <span class="item-meta">
                    <span
                      class="pulse-dot"
                      :class="page.mcpStatusDotClass(server)"
                      :title="page.mcpStatusTitle(server)"
                      aria-hidden="true"
                    />
                    <span class="item-id">{{ server.transport }} · {{ server.id }}</span>
                  </span>
                  <span class="item-tool-count">
                    {{ page.mcpAvailableToolCount(server.id) }}/{{ page.mcpTotalToolCount(server.id) }} 可用
                  </span>
                </div>
              </button>
            </div>
          </div>
          <div v-else-if="!page.loading" class="empty-wrap">
            <NEmpty
              size="small"
              :description="page.mcpServers.length && mcpSearch.trim() ? '无匹配服务' : '暂无 MCP 服务'"
            />
          </div>
        </div>
      </NSpin>
    </aside>

    <main v-if="page.selectedMcp" class="detail-panel">
      <div class="detail-toolbar">
        <div class="detail-toolbar-text">
          <h3 class="detail-heading">{{ page.selectedMcp.displayName || page.selectedMcp.id }}</h3>
          <span class="detail-id">{{ page.selectedMcp.transport }} · {{ page.selectedMcp.id }}</span>
        </div>
        <NSpace :size="8" align="center">
          <NButton
            size="small"
            round
            type="primary"
            class="action-btn"
            :loading="page.probing"
            @click="page.handleProbeMcp"
          >
            <template #icon><NIcon :component="SyncOutline" /></template>
            探测
          </NButton>
          <NDropdown
            trigger="click"
            size="small"
            :options="page.mcpMoreMenuOptions"
            :disabled="page.probing || page.importing || page.saving"
            @select="page.handleMcpMoreSelect"
          >
            <NButton
              size="small"
              quaternary
              class="more-menu-btn"
              title="更多操作"
              aria-label="更多操作"
              :disabled="page.probing || page.importing || page.saving"
            >
              <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
            </NButton>
          </NDropdown>
        </NSpace>
      </div>
      <div class="detail-scroll">
        <section class="form-section mcp-detail-section">
          <NTabs
            v-model:value="page.mcpPanelTab"
            type="line"
            :animated="false"
            class="mcp-panel-tabs"
          >
            <NTabPane name="config" tab="连接配置">
              <div class="mcp-config-pane">
                <header class="mcp-config-toolbar-row">
                  <div class="view-switch">
                    <button
                      type="button"
                      class="view-btn"
                      :class="{ active: page.mcpDetailMode === 'form' }"
                      :disabled="page.mcpDetailEditing"
                      @click="page.mcpDetailMode = 'form'"
                    >
                      表单
                    </button>
                    <button
                      type="button"
                      class="view-btn"
                      :class="{ active: page.mcpDetailMode === 'json' }"
                      :disabled="page.mcpDetailEditing"
                      @click="page.mcpDetailMode = 'json'"
                    >
                      mcp.json
                    </button>
                  </div>
                  <div class="mcp-config-actions">
                    <NButton
                      v-if="!page.mcpDetailEditing"
                      size="small"
                      round
                      secondary
                      @click="page.startMcpDetailEdit"
                    >
                      编辑
                    </NButton>
                    <template v-else>
                      <NButton size="small" round secondary :disabled="page.saving" @click="page.cancelMcpDetailEdit">
                        取消
                      </NButton>
                      <NButton
                        size="small"
                        round
                        type="primary"
                        class="action-btn"
                        :loading="page.saving"
                        @click="page.handleSaveMcpDetail"
                      >
                        保存
                      </NButton>
                    </template>
                  </div>
                </header>
                <div v-if="!page.mcpDetailEditing && page.mcpDetailMode === 'form'" class="info-grid">
                  <div class="info-item">
                    <span class="info-label">展示名</span>
                    <span class="info-value">{{ page.selectedMcp.displayName || page.selectedMcp.id }}</span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">Transport</span>
                    <span class="info-value">{{ page.selectedMcp.transport }}</span>
                  </div>
                  <template v-if="page.selectedMcp.transport === 'stdio'">
                    <div class="info-item">
                      <span class="info-label">Command</span>
                      <span class="info-value mono">{{ page.selectedMcp.command || '—' }}</span>
                    </div>
                    <div class="info-item">
                      <span class="info-label">Args</span>
                      <span class="info-value mono">{{ page.selectedMcp.argsJson || '[]' }}</span>
                    </div>
                    <div class="info-item">
                      <span class="info-label">Env</span>
                      <span class="info-value mono">{{ page.selectedMcp.envJson || '{}' }}</span>
                    </div>
                  </template>
                  <div v-else-if="page.selectedMcp.endpoint" class="info-item">
                    <span class="info-label">Endpoint</span>
                    <span class="info-value mono">{{ page.selectedMcp.endpoint }}</span>
                  </div>
                </div>
                <pre
                  v-else-if="!page.mcpDetailEditing && page.mcpDetailMode === 'json'"
                  class="schema-preview mcp-json-preview"
                >{{ page.buildMcpServerJson(page.selectedMcp) }}</pre>
                <NForm
                  v-else-if="page.mcpDetailEditing && page.mcpDetailMode === 'form'"
                  class="modal-form"
                  label-placement="top"
                  :show-feedback="false"
                >
                  <NFormItem label="展示名">
                    <NInput v-model:value="page.mcpDetailDraft.displayName" class="sun-field" placeholder="可选" />
                  </NFormItem>
                  <NFormItem label="Transport" required>
                    <NSelect
                      v-model:value="page.mcpDetailDraft.transport"
                      class="sun-field"
                      :options="page.transportOptions"
                    />
                  </NFormItem>
                  <NFormItem v-if="page.mcpDetailDraft.transport === 'stdio'" label="Command" required>
                    <NSelect
                      v-model:value="page.mcpDetailDraft.command"
                      class="sun-field"
                      filterable
                      tag
                      :options="page.commandOptions"
                    />
                  </NFormItem>
                  <NFormItem v-if="page.mcpDetailDraft.transport === 'stdio'" label="Args（JSON 数组）">
                    <NInput
                      v-model:value="page.mcpDetailDraft.argsJson"
                      class="sun-field"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 6 }"
                    />
                  </NFormItem>
                  <NFormItem v-if="page.mcpDetailDraft.transport === 'stdio'" label="Env（JSON 对象）">
                    <NInput
                      v-model:value="page.mcpDetailDraft.envJson"
                      class="sun-field"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 4 }"
                    />
                  </NFormItem>
                  <NFormItem v-else label="Endpoint" required>
                    <NInput v-model:value="page.mcpDetailDraft.endpoint" class="sun-field" placeholder="http://..." />
                  </NFormItem>
                </NForm>
                <NInput
                  v-else
                  v-model:value="page.mcpDetailJsonDraft"
                  class="sun-field mcp-json-editor"
                  type="textarea"
                  :autosize="{ minRows: 12, maxRows: 24 }"
                  placeholder="mcp.json 内容"
                />
                <div v-if="page.selectedMcp.lastProbeAt || page.selectedMcp.probeError" class="mcp-probe-meta">
                  <div v-if="page.selectedMcp.lastProbeAt" class="info-item">
                    <span class="info-label">上次探测</span>
                    <span class="info-value">{{ formatSkillVersionTime(page.selectedMcp.lastProbeAt) }}</span>
                  </div>
                  <div v-if="page.selectedMcp.probeError" class="info-item">
                    <span class="info-label">探测错误</span>
                    <span class="info-value error">{{ page.selectedMcp.probeError }}</span>
                  </div>
                </div>
              </div>
            </NTabPane>
            <NTabPane name="tools">
              <template #tab>
                <span class="mcp-tools-tab-label">工具列表</span>
                <NTag :bordered="false" size="tiny" round>{{ page.mcpTools.length }}</NTag>
              </template>
              <div class="mcp-tools-pane">
                <NDataTable
                  v-if="page.mcpTools.length"
                  :columns="page.toolColumns"
                  :data="page.mcpTools"
                  :bordered="false"
                  size="small"
                  class="tools-table"
                />
                <NEmpty v-else size="small" description="暂无工具，请先探测" />
              </div>
            </NTabPane>
          </NTabs>
        </section>
      </div>
    </main>
    <main v-else class="detail-panel detail-empty">
      <NEmpty description="选择左侧 MCP 服务，或新建" />
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

.list-panel .panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px 0;
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.list-search {
  padding: 10px 12px;
  flex-shrink: 0;
}

.search-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.panel-create-btn {
  margin-left: auto;
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

.item-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.item-row-wrap {
  display: flex;
  flex-direction: column;
}

.more-menu-btn {
  padding: 0 6px;
}

:deep(.more-menu-delete) {
  color: var(--n-color-error);
}

.mcp-detail-section {
  padding-top: 8px;
  gap: 0;
}

.mcp-panel-tabs :deep(.n-tabs-nav) {
  padding: 0 4px;
  margin-bottom: 16px;
}

.mcp-config-pane {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mcp-config-toolbar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 4px;
}

.view-switch {
  display: inline-flex;
  flex-shrink: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.view-btn {
  border: none;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: 13px;
  line-height: 1;
  padding: 7px 14px;
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.15s ease;
}

.view-btn + .view-btn {
  border-left: 1px solid var(--sun-border);
}

.view-btn.active {
  color: var(--sun-text);
  font-weight: 600;
}

.view-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.view-btn:not(:disabled):hover {
  color: var(--sun-text);
}

.mcp-tools-tab-label {
  margin-right: 6px;
}

.mcp-tools-pane {
  min-height: 120px;
}

.mcp-config-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

.mcp-json-preview {
  max-height: 360px;
}

.mcp-json-editor :deep(.n-input__textarea-el) {
  font-family: var(--sun-font-mono, monospace);
  font-size: 12px;
  line-height: 1.5;
}

.mcp-probe-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 12px;
  margin-top: 4px;
  border-top: 1px solid var(--sun-border);
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

.item-row-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.item-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.item-tool-count {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  white-space: nowrap;
}

.tools-table :deep(.n-data-table) {
  --n-th-color: var(--sun-black);
  --n-td-color: var(--sun-black);
  --n-border-color: var(--sun-border);
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

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info-label {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.info-value {
  font-size: 14px;
  color: var(--sun-text);
}

.info-value.mono {
  font-family: var(--sun-font-mono, monospace);
  word-break: break-all;
}

.info-value.error {
  color: var(--n-color-error, #e88080);
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
