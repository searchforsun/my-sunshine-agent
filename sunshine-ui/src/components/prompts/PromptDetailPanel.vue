<script setup lang="ts">
import { inject } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NSpace,
  NSpin,
  NTag,
} from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'
import { promptKindLabel } from '../../api/prompts'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi
</script>

<template>
  <main v-if="page.detail" class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-toolbar-text">
        <h3 class="detail-heading">{{ page.detail.displayName }}</h3>
        <span class="detail-id">{{ page.detail.id }}</span>
      </div>
      <NSpace :size="8">
        <NButton
          size="small"
          round
          secondary
          :loading="page.saving"
          @click="page.saveMeta()"
        >
          保存元数据
        </NButton>
        <NButton
          size="small"
          round
          secondary
          :loading="page.saving"
          @click="page.saveVersion('draft')"
        >
          保存草稿
        </NButton>
        <NButton
          size="small"
          round
          type="primary"
          class="action-btn"
          :loading="page.publishing"
          :disabled="!page.hasDraft"
          @click="page.handlePublish()"
        >
          发布最新草稿
        </NButton>
      </NSpace>
    </div>

    <NSpin :show="page.detailLoading" class="detail-spin">
      <div class="detail-scroll">
        <NForm class="detail-form" label-placement="top" :show-feedback="false">
          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">基本信息</h4>
              <NTag size="tiny" :bordered="false">
                {{ promptKindLabel(page.detail.kind) }}
              </NTag>
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
              当前版本 v{{ page.detail.activeVersion }}
              · catalog {{ page.detail.catalogVersion }}
              · {{ page.detail.enabled ? '已启用' : '已停用' }}
            </p>
          </section>

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">内容</h4>
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

          <section class="form-section">
            <header class="form-section-head">
              <h4 class="form-section-title">版本</h4>
            </header>
            <div v-if="page.versions.length" class="version-list">
              <div
                v-for="ver in page.versions"
                :key="ver.version"
                class="version-row"
                :class="{ active: ver.version === page.previewVersion }"
              >
                <button
                  type="button"
                  class="version-main"
                  @click="page.loadVersionIntoEditor(ver)"
                >
                  <span class="version-num">v{{ ver.version }}</span>
                  <NTag
                    size="tiny"
                    :bordered="false"
                    :type="ver.status === 'published' ? 'success' : 'warning'"
                  >
                    {{ ver.status === 'published' ? '已发布' : '草稿' }}
                  </NTag>
                  <span
                    v-if="ver.version === page.detail.activeVersion"
                    class="active-mark"
                  >
                    当前
                  </span>
                  <span class="version-note">{{ ver.changeNote || '—' }}</span>
                </button>
                <NSpace :size="6">
                  <NButton
                    v-if="ver.status === 'draft'"
                    size="tiny"
                    secondary
                    :loading="page.publishing"
                    @click="page.handlePublish(ver.version)"
                  >
                    发布
                  </NButton>
                  <NButton
                    v-if="ver.status === 'published' && ver.version !== page.detail.activeVersion"
                    size="tiny"
                    quaternary
                    :loading="page.rollingBack"
                    @click="page.handleRollback(ver.version)"
                  >
                    回滚
                  </NButton>
                </NSpace>
              </div>
            </div>
            <NEmpty v-else size="small" description="暂无版本" />
          </section>
        </NForm>
      </div>
    </NSpin>
  </main>
  <main v-else class="detail-panel detail-empty">
    <NEmpty description="选择左侧提示词，或新建" />
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

.version-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.version-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
}

.version-row.active {
  box-shadow: inset 0 0 0 1px var(--sun-accent);
  border-color: var(--sun-accent);
}

.version-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  border: none;
  background: transparent;
  color: var(--sun-text);
  cursor: pointer;
  padding: 0;
  text-align: left;
}

.version-num {
  font-weight: 600;
  font-family: var(--sun-font-mono, monospace);
  flex-shrink: 0;
}

.active-mark {
  font-size: 11px;
  color: var(--sun-accent);
  flex-shrink: 0;
}

.version-note {
  font-size: 12px;
  color: var(--sun-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
}
</style>
