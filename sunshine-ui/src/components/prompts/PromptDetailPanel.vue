<script setup lang="ts">
import { inject } from 'vue'
import {
  NButton,
  NDropdown,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpin,
  NTag,
} from 'naive-ui'
import { EllipsisHorizontal } from '@vicons/ionicons5'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'
import { promptKindLabel } from '../../api/prompts'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi
</script>

<template>
  <main v-if="page.detail" class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-title-block">
        <h3 class="detail-heading">{{ page.detail.displayName }}</h3>
        <div class="detail-meta-inline">
          <span class="detail-id">{{ page.detail.id }}</span>
          <NTag size="tiny" :bordered="false">
            {{ promptKindLabel(page.detail.kind) }}
          </NTag>
        </div>
      </div>
      <div class="detail-actions">
        <div v-if="page.showVersionSelect" class="version-row">
          <span class="version-label">当前版本</span>
          <NTag
            v-if="page.selectedVersionStatus"
            size="small"
            :bordered="false"
            round
            :type="page.detailVersionTagType"
          >
            {{ page.selectedVersionStatusLabel }}
          </NTag>
          <NSelect
            v-model:value="page.selectedVersion"
            :options="page.versionOptions"
            size="small"
            class="version-select"
            placeholder="选择版本"
            :disabled="page.isActionBusy"
            :menu-props="{ class: 'version-select-menu' }"
            @update:value="page.onVersionSelected"
          />
        </div>
        <NButton
          v-if="page.showPrimaryPublishButton"
          size="small"
          round
          type="primary"
          class="action-btn"
          :loading="page.publishing"
          :disabled="page.isActionBusy"
          @click="page.handlePrimaryPublish()"
        >
          {{ page.primaryPublishLabel }}
        </NButton>
        <NDropdown
          trigger="click"
          size="small"
          :options="page.moreMenuOptions"
          :disabled="page.isActionBusy"
          @select="page.handleMoreMenuSelect"
        >
          <NButton
            size="small"
            quaternary
            class="more-menu-btn"
            title="版本操作"
            aria-label="版本与元数据操作"
            :loading="page.isActionBusy"
            :disabled="page.isActionBusy"
          >
            <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
          </NButton>
        </NDropdown>
      </div>
    </div>

    <NSpin :show="page.detailLoading" class="detail-spin">
      <div class="detail-scroll">
        <NForm class="detail-form" label-placement="top" :show-feedback="false">
          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">基本信息</h4>
            </header>
            <div class="form-grid">
              <NFormItem label="展示名">
                <NInput v-model:value="page.editDisplayName" class="sun-field" />
              </NFormItem>
              <NFormItem v-if="page.detail.kind === 'routing-rule'" label="优先级">
                <NInputNumber
                  v-model:value="page.editPriority"
                  class="sun-field"
                  :min="0"
                  :show-button="false"
                />
              </NFormItem>
            </div>
            <NFormItem label="描述">
              <NInput
                v-model:value="page.editDescription"
                class="sun-field sun-field-grow"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 6 }"
              />
            </NFormItem>
            <p class="meta-line">
              生效版本 v{{ page.detail.activeVersion }}
              · catalog {{ page.detail.catalogVersion }}
              · {{ page.detail.enabled ? '已启用' : '已停用' }}
            </p>
          </section>

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">内容</h4>
              <NButton
                size="small"
                round
                secondary
                :loading="page.saving"
                :disabled="page.isActionBusy"
                @click="page.saveVersion('draft')"
              >
                保存草稿
              </NButton>
            </header>
            <NFormItem label="变更说明">
              <NInput
                v-model:value="page.editChangeNote"
                class="sun-field"
                placeholder="本次修改说明（可选）"
              />
            </NFormItem>
            <NFormItem label="contentText">
              <NInput
                v-model:value="page.editContentText"
                class="sun-field sun-field-grow content-input"
                type="textarea"
                :autosize="{ minRows: 8, maxRows: 28 }"
                placeholder="文本内容"
              />
            </NFormItem>
            <NFormItem label="contentJson">
              <NInput
                v-model:value="page.editContentJson"
                class="sun-field sun-field-grow content-input mono"
                type="textarea"
                :autosize="{ minRows: 4, maxRows: 20 }"
                placeholder="JSON 内容（可选）"
              />
            </NFormItem>
          </section>
        </NForm>
      </div>
    </NSpin>
  </main>
  <main v-else class="detail-panel detail-empty">
    <NEmpty description="选择左侧提示词" />
  </main>
</template>

<style scoped>
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
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
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  padding: 18px 22px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.detail-title-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
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

.detail-meta-inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.detail-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  flex-shrink: 0;
  min-height: 28px;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-label {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.version-select {
  width: min(228px, 44vw);
}

.version-select :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-arrow-color: var(--sun-text-secondary) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-active: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
  --n-box-shadow-hover: none !important;
  --n-box-shadow-active: none !important;
}

.more-menu-btn {
  padding: 0 6px;
}

.detail-spin {
  flex: 1;
  min-height: 0;
}

.detail-spin :deep(.n-spin-content) {
  height: 100%;
}

.detail-scroll {
  height: 100%;
  overflow-y: auto;
  padding: 22px;
}

.detail-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-form :deep(.n-form-item) {
  margin-bottom: 0;
}

.detail-form :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding-bottom: 8px;
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px 20px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.form-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--sun-border);
}

.form-section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.meta-line {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.content-input :deep(.n-input__textarea-el) {
  font-size: var(--sun-font-base, 14px);
  line-height: 1.6;
}

.content-input.mono :deep(.n-input__textarea-el) {
  font-family: var(--sun-font-mono, monospace);
  font-size: 12px;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
}
</style>

<style>
.version-select-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
  --n-option-color-active: transparent !important;
  --n-option-color-active-pending: var(--sun-row-hover) !important;
  --n-option-color-pending: var(--sun-row-hover) !important;
  --n-option-text-color: var(--sun-text) !important;
  --n-option-text-color-active: var(--sun-text) !important;
  --n-option-check-color: var(--sun-text) !important;
  background: var(--sun-black) !important;
  border: 1px solid var(--sun-border) !important;
  box-shadow: var(--shadow-elevated) !important;
}
</style>
