<script setup lang="ts">
import { inject } from 'vue'
import {
  NButton,
  NDropdown,
  NEmpty,
  NIcon,
  NInput,
  NSelect,
  NSpin,
  NTag,
  NText,
  NTree,
} from 'naive-ui'
import CopyToggleIcon from '../icons/CopyToggleIcon.vue'
import {
  CreateOutline,
  DocumentTextOutline,
  FolderOpenOutline,
  EllipsisHorizontal,
} from '@vicons/ionicons5'
import { formatFileSize } from '../../utils/buildFileTree'
import { versionStatusLabel } from '../../utils/skills/skillsVersionUtils'
import { SKILLS_PAGE_KEY, type SkillsPageApi } from '../../composables/useSkillsPage'

const page = inject(SKILLS_PAGE_KEY) as SkillsPageApi
</script>

<template>
  <main class="detail-panel">
    <div class="detail-panel-inner">
      <div class="detail-toolbar">
        <div class="detail-title-block">
          <div class="detail-name-row">
            <h3>{{ page.selectedSkill!.id }}</h3>
          </div>
          <div class="detail-meta-inline">
            <span
              v-if="page.selectedSkill!.displayName && page.selectedSkill!.displayName !== page.selectedSkill!.id"
              class="detail-subtitle"
            >
              {{ page.selectedSkill!.displayName }}
            </span>
            <span v-if="page.detailMaintainerText" class="detail-maintainer">
              {{ page.detailMaintainerText }}
            </span>
          </div>
        </div>
        <div v-show="!page.detailLoading || page.isActionBusy" class="detail-actions">
          <div v-if="page.showVersionSelect" class="version-row">
            <span class="version-label">当前版本</span>
            <NTag
              v-if="page.selectedVersionStatus"
              size="small"
              :bordered="false"
              round
              :type="page.detailVersionTagType"
            >
              {{ versionStatusLabel(page.selectedVersionStatus) }}
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
              aria-label="版本与文件操作"
              :loading="page.isActionBusy"
              :disabled="page.isActionBusy"
            >
              <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
            </NButton>
          </NDropdown>
        </div>
      </div>

      <div v-show="!page.isDetailBusy" class="detail-content">
        <div v-if="page.skillPhase === 'setup' && !page.isDetailBusy" class="setup-panel">
          <NEmpty description="选择含 SKILL.md 的文件夹完成首次上传">
            <template #extra>
              <label for="skill-folder-picker" class="folder-picker-label" @click="page.onPickLabelClick">
                <NButton tag="span" type="primary" class="upload-folder-btn" :loading="page.uploading">
                  <template #icon><NIcon :component="FolderOpenOutline" /></template>
                  选择 Skill 文件夹
                </NButton>
              </label>
            </template>
          </NEmpty>
        </div>
        <div v-else class="explorer">
          <div class="file-tree-pane">
            <div class="tree-scroll">
              <NTree
                v-if="page.treeOptions.length"
                block-line
                selectable
                expand-on-click
                :data="page.treeOptions"
                :expanded-keys="page.expandedKeys"
                :selected-keys="page.selectedFilePath ? [page.selectedFilePath] : []"
                :render-prefix="page.renderTreePrefix"
                @update:expanded-keys="(k) => (page.expandedKeys = k.map(String))"
                @update:selected-keys="page.onTreeSelect"
              />
              <NEmpty v-else-if="!page.isDetailBusy" size="small" description="无文件" />
            </div>
          </div>
          <div class="file-preview-pane">
            <div v-if="page.selectedFilePath" class="preview-bar">
              <NIcon :component="DocumentTextOutline" :size="14" />
              <span class="preview-path">{{ page.selectedFilePath }}</span>
              <div v-if="page.canEditCurrentFile" class="preview-bar-actions">
                <template v-if="page.fileEditMode">
                  <NButton size="tiny" quaternary :disabled="page.savingFile" @click="page.handleCancelFileEdit">取消</NButton>
                  <NButton
                    size="tiny"
                    type="primary"
                    class="action-btn"
                    :loading="page.savingFile"
                    :disabled="!page.fileEditDirty"
                    @click="page.handleSaveFileEdit"
                  >
                    保存
                  </NButton>
                </template>
                <NButton
                  v-else
                  size="tiny"
                  quaternary
                  title="编辑当前草稿"
                  @click="page.enterFileEditMode"
                >
                  <template #icon><NIcon :component="CreateOutline" /></template>
                  在线编辑
                </NButton>
              </div>
              <button
                v-if="page.showPreviewCopy && !page.fileEditMode"
                type="button"
                class="preview-copy-btn smd-toolbtn"
                :title="page.copyPreviewDone ? '已复制' : '复制'"
                @click="page.copyPreviewContent"
              >
                <CopyToggleIcon :copied="page.copyPreviewDone" />
              </button>
            </div>
            <div :ref="page.bindPreviewScrollRef" class="preview-scroll">
              <div v-if="page.fileLoading" class="preview-loading-pane">
                <NSpin size="small" />
              </div>
              <div v-else-if="page.fileEditMode" class="preview-editor-wrap">
                <NInput
                  v-model:value="page.fileEditDraft"
                  type="textarea"
                  :autosize="{ minRows: 12, maxRows: 40 }"
                  class="preview-editor"
                  placeholder="编辑文件内容…"
                />
              </div>
              <div v-else-if="page.previewImageSrc" class="preview-image-wrap">
                <img :src="page.previewImageSrc" :alt="page.selectedFilePath ?? ''" class="preview-image" />
              </div>
              <div v-else-if="page.fileContent?.binary" class="preview-binary">
                <NText depth="3">
                  二进制文件（{{ page.fileContent.contentType }}），约 {{ formatFileSize(Math.round(page.fileContent.content.length * 0.75)) }}
                </NText>
              </div>
              <div
                v-else-if="page.previewHtml"
                :key="`${page.selectedId}-${page.selectedVersion}-${page.selectedFilePath}`"
                class="msg-md skill-md-preview"
                v-html="page.previewHtml"
              />
              <div
                v-else-if="page.previewCodeHtml"
                :key="`${page.selectedId}-${page.selectedVersion}-${page.selectedFilePath}-code`"
                class="skill-file-plain"
              >
                <pre class="skill-file-plain-pre"><code :class="page.previewCodeLangClass" v-html="page.previewCodeHtml" /></pre>
              </div>
              <pre
                v-else-if="page.previewPlain"
                :key="`${page.selectedId}-${page.selectedVersion}-${page.selectedFilePath}-plain`"
                class="skill-file-plain skill-file-plain-pre"
              ><code>{{ page.previewPlain }}</code></pre>
              <div v-else-if="!page.isDetailBusy && page.selectedFilePath" class="preview-loading-pane">
                <NSpin size="small" />
              </div>
              <div v-else-if="!page.isDetailBusy" class="preview-empty">
                <NEmpty size="small" description="选择左侧文件预览" />
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="page.isDetailBusy" class="detail-panel-overlay">
        <NSpin size="medium" />
        <NText depth="3">{{ page.layoutBusyText }}</NText>
      </div>
    </div>
  </main>
</template>

<style scoped>
.detail-panel {
  display: flex;
  flex-direction: column;
  padding: 16px;
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  overflow: hidden;
}

.detail-panel-inner {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-panel-overlay {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: var(--sun-black);
  border-radius: var(--radius-md);
}

.detail-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.setup-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
}

.detail-title-block h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
  letter-spacing: -0.2px;
  font-family: 'JetBrains Mono', monospace;
}

.detail-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-meta-inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}

.detail-subtitle {
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.detail-maintainer {
  font-size: 12px;
  color: var(--sun-text-muted);
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

.preview-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
  background: transparent;
}

.preview-bar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  flex-shrink: 0;
}

.preview-editor-wrap {
  padding: 12px;
  height: 100%;
  box-sizing: border-box;
}

.preview-editor {
  width: 100%;
  font-family: var(--sun-font-mono, 'JetBrains Mono', monospace);
  font-size: var(--sun-font-base);
  --n-font-size: var(--sun-font-base) !important;
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.preview-copy-btn {
  margin-left: auto;
  flex-shrink: 0;
}

.preview-bar-actions + .preview-copy-btn {
  margin-left: 0;
}

.upload-folder-btn {
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

.folder-picker-label {
  cursor: pointer;
  display: inline-flex;
}

.explorer {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(220px, 260px) 1fr;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--sun-black);
  position: relative;
}

.file-tree-pane,
.file-preview-pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.file-tree-pane {
  border-right: 1px solid var(--sun-border);
}

.preview-path {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 500;
  font-size: 11px;
}

.tree-scroll,
.preview-scroll {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.file-tree-pane :deep(.n-tree) {
  --n-node-color-active: transparent !important;
  --n-node-color-hover: var(--sun-row-hover) !important;
  font-size: 13px;
}

.file-tree-pane :deep(.n-tree-node--selected .n-tree-node-content__text) {
  font-weight: 600;
  color: var(--sun-text);
}

.file-tree-pane :deep(.n-tree-node-content) {
  border-radius: var(--radius-sm);
}

.file-tree-pane :deep(.tree-size) {
  font-size: 10px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
  margin-left: 4px;
}

.file-tree-pane :deep(.tree-icon-dir) {
  color: var(--sun-amber);
}

.file-tree-pane :deep(.tree-icon-file) {
  color: var(--sun-text-secondary);
}

.preview-image-wrap {
  padding: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
}

.preview-image {
  max-width: 100%;
  max-height: 480px;
  object-fit: contain;
  border-radius: var(--radius-sm);
  border: 1px solid var(--sun-border);
}

.preview-binary {
  padding: 16px;
  text-align: center;
}

.preview-loading-pane {
  flex: 1;
  min-height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
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

.more-menu-btn {
  padding: 0 6px;
}

@media (max-width: 960px) {
  .explorer {
    grid-template-columns: 1fr;
    grid-template-rows: 200px 1fr;
  }

  .file-tree-pane {
    border-right: none;
    border-bottom: 1px solid var(--sun-border);
  }
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
