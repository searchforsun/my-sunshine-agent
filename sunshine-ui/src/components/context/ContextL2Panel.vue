<script setup lang="ts">
import { inject } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSpin,
  NTag,
} from 'naive-ui'
import { SearchOutline } from '@vicons/ionicons5'
import CopyToggleIcon from '../icons/CopyToggleIcon.vue'
import { CONTEXT_PAGE_KEY, type ContextPageApi } from '../../composables/useContextPage'

const page = inject(CONTEXT_PAGE_KEY) as ContextPageApi
</script>

<template>
  <template v-if="!page.selectedConv">
    <div class="empty-wrap fill">
      <NEmpty size="small" description="请选择会话" />
    </div>
  </template>
  <NSpin v-else :show="page.loading" class="tab-spin">
    <div v-if="!page.entries.length" class="empty-wrap fill">
      <NEmpty size="small" description="暂无跨会话状态（对话后异步抽取）" />
    </div>
    <div v-else class="l2-layout">
      <div class="l2-list-col">
        <div class="l2-filters">
          <NInput
            v-model:value="page.l2Search"
            placeholder="筛选 key / 值…"
            size="small"
            round
            clearable
            class="search-input"
          >
            <template #prefix>
              <NIcon :component="SearchOutline" :size="14" />
            </template>
          </NInput>
          <NSelect
            v-model:value="page.l2StatusFilter"
            class="sun-field l2-status-filter"
            size="small"
            clearable
            placeholder="全部状态"
            :options="page.l2StatusFilterOptions"
          />
        </div>
        <div v-if="page.filteredL2Entries.length" class="l2-list">
          <button
            v-for="item in page.filteredL2Entries"
            :key="item.id"
            type="button"
            class="l2-row"
            :class="{ active: item.id === page.selectedL2Id }"
            @click="page.selectL2(item.id)"
          >
            <div class="l2-row-head">
              <NTag size="tiny" :type="page.kindMeta(item.kind).type" :bordered="false" round>
                {{ page.kindMeta(item.kind).label }}
              </NTag>
              <NTag size="tiny" :type="page.statusType(item.status)" :bordered="false">
                {{ page.statusLabel(item.status) }}
              </NTag>
            </div>
            <span class="l2-key">{{ item.stateKey }}</span>
            <p class="l2-value">{{ item.stateValue }}</p>
          </button>
        </div>
        <div v-else class="empty-wrap l2-filter-empty">
          <NEmpty size="small" description="无匹配条目" />
        </div>
      </div>
      <div v-if="page.selectedL2" class="edit-pane">
        <div class="edit-head">
          <div class="edit-title-block">
            <div class="edit-title-row">
              <NTag size="small" :type="page.kindMeta(page.selectedL2.kind).type" :bordered="false" round>
                {{ page.kindMeta(page.selectedL2.kind).label }}
              </NTag>
              <h3 class="detail-title">{{ page.selectedL2.stateKey }}</h3>
            </div>
          </div>
          <NSpace :size="8">
            <NButton
              size="small"
              secondary
              :loading="page.voiding"
              :disabled="page.selectedL2.status === 'void'"
              @click="page.handleVoid"
            >
              作废
            </NButton>
            <NButton
              size="small"
              type="primary"
              class="action-btn"
              :loading="page.saving"
              :disabled="!page.isFormDirty"
              @click="page.handleSave"
            >
              保存
            </NButton>
          </NSpace>
        </div>
        <NForm class="detail-form" label-placement="top" :show-feedback="false">
          <NFormItem label="值">
            <NInput
              v-model:value="page.editForm.stateValue"
              class="sun-field sun-field-grow"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 10 }"
            />
          </NFormItem>
          <div class="form-grid">
            <NFormItem label="置信度">
              <NInputNumber
                v-model:value="page.editForm.confidence"
                class="sun-field"
                :min="0"
                :max="1"
                :step="0.05"
              />
            </NFormItem>
            <NFormItem label="状态">
              <NSelect
                v-model:value="page.editForm.status"
                class="sun-field"
                :options="page.statusOptions"
              />
            </NFormItem>
          </div>
        </NForm>
        <div class="meta-block">
          <div class="meta-row">
            <span class="meta-label">条目 ID</span>
            <div class="meta-value-row">
              <code class="meta-id">{{ page.selectedL2.id }}</code>
              <button
                type="button"
                class="copy-btn smd-toolbtn"
                :title="page.copiedL2Key === 'id' ? '已复制' : '复制'"
                @click="page.copyL2Field('id', page.selectedL2.id)"
              >
                <CopyToggleIcon :copied="page.copiedL2Key === 'id'" />
              </button>
            </div>
          </div>
          <div class="meta-row">
            <span class="meta-label">溯源消息</span>
            <div class="meta-value-row">
              <code class="meta-id">{{ page.selectedL2.sourceMsgId || '—' }}</code>
              <button
                v-if="page.selectedL2.sourceMsgId"
                type="button"
                class="copy-btn smd-toolbtn"
                :title="page.copiedL2Key === 'source' ? '已复制' : '复制'"
                @click="page.copyL2Field('source', page.selectedL2.sourceMsgId)"
              >
                <CopyToggleIcon :copied="page.copiedL2Key === 'source'" />
              </button>
            </div>
          </div>
          <div class="meta-row">
            <span class="meta-label">过期时间</span>
            <span class="meta-text">{{ page.formatTime(page.selectedL2.expiresAt) }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-label">更新时间</span>
            <span class="meta-text">{{ page.formatTime(page.selectedL2.updatedAt) }}</span>
          </div>
        </div>
      </div>
      <div v-else class="empty-wrap">
        <NEmpty size="small" description="选择左侧条目进行编辑" />
      </div>
    </div>
  </NSpin>
</template>

<style scoped>
.tab-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}

.tab-spin :deep(.n-spin-container),
.tab-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
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

.l2-layout {
  display: grid;
  grid-template-columns: minmax(200px, 260px) 1fr;
  gap: 12px;
  min-height: 0;
  height: 100%;
}

.l2-list-col {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.l2-filters {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}

.l2-status-filter {
  width: 100%;
}

.l2-list {
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.l2-filter-empty {
  flex: 1;
  min-height: 120px;
}

.l2-row {
  text-align: left;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--sun-text);
  padding: 10px 12px;
  cursor: pointer;
}

.l2-row:hover {
  border-color: var(--sun-border-strong, var(--sun-text-muted));
}

.l2-row.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.l2-row-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.l2-key {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
}

.l2-value {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.edit-pane {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  overflow: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.edit-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--sun-border);
}

.edit-title-block {
  min-width: 0;
}

.edit-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.detail-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-form :deep(.n-form-item) {
  margin-bottom: 14px;
}

.detail-form :deep(.n-form-item-label) {
  padding-bottom: 6px !important;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 16px;
}

.meta-block {
  margin-top: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--sun-border);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 8px 12px;
  align-items: start;
  font-size: 12px;
}

.meta-label {
  color: var(--sun-text-muted);
  line-height: 22px;
}

.meta-value-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  min-width: 0;
}

.meta-id {
  flex: 1;
  min-width: 0;
  margin: 0;
  padding: 2px 0;
  font-family: var(--sun-font-mono, ui-monospace, monospace);
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text);
  word-break: break-all;
  white-space: pre-wrap;
}

.meta-text {
  color: var(--sun-text);
  line-height: 22px;
}

.copy-btn {
  flex-shrink: 0;
  margin-top: 0;
  color: var(--sun-text-muted);
}

.copy-btn:hover {
  color: var(--sun-text);
}

.detail-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.meta-line {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--sun-text-muted);
  word-break: break-all;
}

.empty-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  width: 100%;
}

.empty-wrap.fill {
  min-height: 0;
  height: 100%;
  align-self: stretch;
}

:deep(.sun-field .n-input),
:deep(.sun-field .n-input-wrapper),
:deep(.sun-field .n-base-selection),
:deep(.sun-field .n-input-number) {
  background: var(--sun-black) !important;
}

@media (max-width: 960px) {
  .l2-layout {
    grid-template-columns: 1fr;
  }
}
</style>
