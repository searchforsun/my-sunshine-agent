<script setup lang="ts">
import {
  NButton,
  NDataTable,
  NDatePicker,
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
  NTabPane,
  NTabs,
} from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import { useBizDataPage } from '../composables/useBizDataPage'

const {
  domains,
  loading,
  saving,
  deleting,
  domain,
  rows,
  showFormModal,
  formMode,
  formDraft,
  showDeleteModal,
  tableDefs,
  currentTable,
  userOptions,
  columns,
  rowKeyOf,
  refresh,
  selectTable,
  openCreate,
  draftText,
  draftNumber,
  draftUser,
  draftYearFormatted,
  setDraft,
  setYearDraft,
  selectOptionsFor,
  submitForm,
  confirmDelete,
} = useBizDataPage()
</script>

<template>
  <div class="biz-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>业务数据</h2>
      </div>
      <NSpace :size="8">
        <NButton round secondary :loading="loading" @click="refresh">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
        <NButton round type="primary" class="action-btn" @click="openCreate">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
      </NSpace>
    </header>

    <div class="domain-tabs">
      <NTabs v-model:value="domain" type="line" size="small" animated>
        <NTabPane v-for="d in domains" :key="d.key" :name="d.key" :tab="d.label" />
      </NTabs>
    </div>

    <div class="biz-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">数据表</span>
          <span class="panel-count">{{ tableDefs.length }}</span>
        </div>
        <div class="list-body">
          <button
            v-for="t in tableDefs"
            :key="t.key"
            type="button"
            class="table-card"
            :class="{ active: t.key === currentTable.key }"
            @click="selectTable(t.key)"
          >
            <span class="table-label">{{ t.label }}</span>
            <span class="table-id">{{ t.key }}</span>
          </button>
        </div>
      </aside>

      <section class="detail-panel">
        <div class="panel-head detail-head">
          <span class="panel-title">{{ currentTable.label }}</span>
          <span class="panel-count">{{ rows.length }}</span>
        </div>
        <NSpin :show="loading" size="small" class="detail-spin">
          <div class="detail-body">
            <NDataTable
              v-if="rows.length > 0"
              :columns="columns"
              :data="rows"
              :row-key="rowKeyOf"
              :bordered="false"
              size="small"
              class="biz-table"
            />
            <div v-else-if="!loading" class="detail-empty">
              <NEmpty description="暂无数据，点击右上角新建" />
            </div>
          </div>
        </NSpin>
      </section>
    </div>

    <NModal
      v-model:show="showFormModal"
      preset="dialog"
      :title="formMode === 'create' ? `新建 · ${currentTable.label}` : `编辑 · ${currentTable.label}`"
      class="sunshine-dialog"
      style="width: 520px"
    >
      <NForm class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem
          v-for="f in currentTable.fields"
          :key="f.key"
          :label="f.label"
          :required="f.required"
        >
          <NSelect
            v-if="f.kind === 'user'"
            :value="draftUser(f.key)"
            class="sun-field"
            filterable
            clearable
            :options="userOptions"
            :placeholder="`选择${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
          <NSelect
            v-else-if="f.kind === 'select'"
            :value="draftText(f.key)"
            class="sun-field"
            filterable
            tag
            clearable
            :options="selectOptionsFor(f)"
            :placeholder="`选择或输入${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
          <NDatePicker
            v-else-if="f.kind === 'date'"
            class="sun-field sun-field-grow"
            type="date"
            clearable
            :formatted-value="draftText(f.key)"
            value-format="yyyy-MM-dd"
            :placeholder="`选择${f.label}`"
            @update:formatted-value="(v) => setDraft(f.key, v)"
          />
          <NDatePicker
            v-else-if="f.kind === 'month'"
            class="sun-field sun-field-grow"
            type="month"
            clearable
            :formatted-value="draftText(f.key)"
            value-format="yyyy-MM"
            :placeholder="`选择${f.label}`"
            @update:formatted-value="(v) => setDraft(f.key, v)"
          />
          <NDatePicker
            v-else-if="f.kind === 'year'"
            class="sun-field sun-field-grow"
            type="year"
            clearable
            :formatted-value="draftYearFormatted(f.key)"
            value-format="yyyy"
            :placeholder="`选择${f.label}`"
            @update:formatted-value="(v) => setYearDraft(f.key, v)"
          />
          <NInputNumber
            v-else-if="f.kind === 'number'"
            :value="draftNumber(f.key)"
            class="sun-field sun-field-grow"
            clearable
            :show-button="false"
            :placeholder="`输入${f.label}`"
            @update:value="(v) => { formDraft[f.key] = v }"
          />
          <NInput
            v-else-if="f.kind === 'textarea'"
            :value="draftText(f.key) ?? ''"
            class="sun-field sun-field-grow"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            :placeholder="`输入${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
          <NInput
            v-else
            :value="draftText(f.key) ?? ''"
            class="sun-field"
            clearable
            :placeholder="`输入${f.label}`"
            @update:value="(v) => setDraft(f.key, v)"
          />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showFormModal = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="saving" @click="submitForm">
          {{ formMode === 'create' ? '创建' : '保存' }}
        </NButton>
      </template>
    </NModal>

    <NModal
      v-model:show="showDeleteModal"
      preset="dialog"
      title="删除确认"
      class="sunshine-dialog"
    >
      <p>确定删除该条「{{ currentTable.label }}」记录？此操作不可恢复。</p>
      <template #action>
        <NButton @click="showDeleteModal = false">取消</NButton>
        <NButton type="error" :loading="deleting" @click="confirmDelete">删除</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.biz-root {
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

.domain-tabs {
  flex-shrink: 0;
}

.domain-tabs :deep(.n-tabs-nav) {
  --n-tab-text-color: var(--sun-text-muted);
  --n-tab-text-color-active: var(--sun-text);
  --n-tab-text-color-hover: var(--sun-text);
  --n-bar-color: var(--sun-text);
  --n-pane-padding-top: 0;
  --n-tab-border-color: var(--sun-border);
}

.biz-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(240px, 280px) 1fr;
  gap: 16px;
}

.list-panel,
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-count {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.list-body {
  padding: 12px 14px 14px;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.table-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.table-card:hover {
  border-color: var(--sun-border-light);
}

.table-card.active {
  box-shadow: inset 0 0 0 1px var(--sun-text);
  border-color: var(--sun-text);
}

.table-label {
  font-size: 13px;
  font-weight: 600;
}

.table-card.active .table-label {
  font-weight: 700;
}

.table-id {
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, ui-monospace, monospace);
}

.detail-spin {
  flex: 1;
  min-height: 0;
}

.detail-spin :deep(.n-spin-content) {
  height: 100%;
}

.detail-body {
  padding: 12px 16px 16px;
  min-height: 0;
  height: 100%;
  overflow: auto;
  box-sizing: border-box;
}

.detail-empty {
  height: 100%;
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.biz-table :deep(.n-data-table) {
  --n-th-color: transparent;
  --n-td-color: transparent;
  --n-th-color-hover: transparent;
  --n-td-color-hover: transparent;
  --n-border-color: var(--sun-border);
  --n-th-text-color: var(--sun-text-muted);
  --n-td-text-color: var(--sun-text);
}

.biz-table :deep(.n-data-table-th),
.biz-table :deep(.n-data-table-td) {
  background: transparent !important;
}

.modal-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.action-btn {
  min-width: 96px;
}

.sun-field {
  width: 100%;
}

.sun-field-grow {
  width: 100%;
}

.sun-field :deep(.n-input),
.sun-field :deep(.n-input-wrapper),
.sun-field :deep(.n-base-selection),
.sun-field :deep(.n-input-number),
.sun-field :deep(.n-date-picker) {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  background: var(--sun-black) !important;
  width: 100%;
}

.sun-field :deep(.n-input__input-el),
.sun-field :deep(.n-input__textarea-el),
.sun-field :deep(.n-base-selection-input),
.sun-field :deep(.n-input-number-input),
.sun-field :deep(.n-input__border),
.sun-field :deep(.n-date-picker .n-input) {
  color: var(--sun-text) !important;
}

.sun-field :deep(.n-date-picker) {
  width: 100%;
}
</style>
