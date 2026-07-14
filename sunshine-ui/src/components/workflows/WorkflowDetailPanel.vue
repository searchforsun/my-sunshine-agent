<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import {
  NButton,
  NDropdown,
  NIcon,
  NSelect,
  NSpin,
  NTag,
} from 'naive-ui'
import { EllipsisHorizontal } from '@vicons/ionicons5'
import WorkflowDagEditor from './WorkflowDagEditor.vue'
import WorkflowStudioCanvasToolbar from './WorkflowStudioCanvasToolbar.vue'
import WorkflowStudioExpandLayer from './WorkflowStudioExpandLayer.vue'
import WorkflowStudioPropsColumn from './WorkflowStudioPropsColumn.vue'
import WorkflowTemplateModal from './WorkflowTemplateModal.vue'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import { FLOW_CONFIG_SELECTION } from '../../utils/workflowPlan'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const studioExpanded = ref(false)
const propsPanelOpen = ref(false)

const canvasFitViewKey = computed(() => {
  if (page.selectedId == null || page.editVersion == null) return null
  return `${page.selectedId}:${page.editVersion}`
})

const validationAlertExpanded = ref(true)

watch(
  () => page.validationIssues.length,
  (count, prev) => {
    if (count > 0 && count !== prev) {
      validationAlertExpanded.value = true
    }
  },
)

const validationCollapsedLine = computed(() => {
  const issues = page.validationIssues
  if (issues.length === 0) return ''
  const head = `DAG 校验问题（${issues.length}）`
  return issues.length === 1 ? `${head} · ${issues[0]}` : `${head} · ${issues[0]}…`
})

function toggleValidationAlert(): void {
  validationAlertExpanded.value = !validationAlertExpanded.value
}

const readOnly = computed(() => !page.canEditPlan)

function onSelectNode(nodeId: string | null) {
  page.selectedNodeId = nodeId ?? FLOW_CONFIG_SELECTION
  propsPanelOpen.value = true
}
</script>


<template>
  <section v-if="page.selectedWorkflow && page.plan" class="detail-panel">
    <header class="detail-toolbar">
      <div class="detail-title-block">
        <h3>{{ page.selectedWorkflow.displayName }}</h3>
        <div class="detail-meta-inline">
          <span class="detail-id">#{{ page.selectedWorkflow.id }}</span>
        </div>
      </div>
      <div class="detail-actions">
        <div v-if="page.versionOptions.length" class="version-row">
          <span class="version-label">当前版本</span>
          <NTag
            size="small"
            :bordered="false"
            round
            :type="page.versionStatusTagType(page.versionStatus)"
          >
            {{ page.versionStatusLabel(page.versionStatus) }}
          </NTag>
          <NSelect
            :value="page.selectedVersion"
            :options="page.versionOptions"
            size="small"
            class="version-select"
            placeholder="选择版本"
            :disabled="page.detailLoading || page.saving"
            :menu-props="{ class: 'version-select-menu' }"
            @update:value="v => { page.selectedVersion = v as number }"
          />
          <NButton
            v-if="page.canCompareVersions"
            size="small"
            round
            secondary
            title="对比当前版本与上一版本"
            @click="page.openVersionDiff()"
          >
            对比
          </NButton>
        </div>
        <div class="publish-row">
          <NButton
            v-if="page.canTryInChat"
            size="small"
            round
            secondary
            title="在 Chat 中试用已发布工作流"
            @click="page.openInChat()"
          >
            在 Chat 试用
          </NButton>
          <NButton
            size="small"
            round
            secondary
            title="全屏画布"
            @click="studioExpanded = true"
          >
            全屏画布
          </NButton>
          <NButton
            v-if="page.showSaveDraftButton"
            size="small"
            round
            secondary
            :loading="page.saving"
            @click="void page.saveDraft()"
          >
            保存草稿
          </NButton>
          <NButton
            v-if="page.showPublishButton"
            size="small"
            round
            type="primary"
            class="action-btn"
            :loading="page.publishing"
            @click="void page.publish()"
          >
            {{ page.workflowPhase === 'history' ? '设为此生效版' : '发布' }}
          </NButton>
          <NDropdown
            trigger="click"
            size="small"
            :options="page.moreMenuOptions"
            @select="(key) => page.handleMoreMenuSelect(String(key))"
          >
            <NButton
              size="small"
              quaternary
              class="more-menu-btn"
              title="版本操作"
              aria-label="版本操作"
              :loading="page.saving || page.publishing"
            >
              <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
            </NButton>
          </NDropdown>
        </div>
      </div>
    </header>

    <div
      v-if="page.validationIssues.length"
      class="validation-alert"
      :class="{ 'is-collapsed': !validationAlertExpanded, 'is-expanded': validationAlertExpanded }"
    >
      <button
        type="button"
        class="validation-alert-head"
        :aria-expanded="validationAlertExpanded"
        aria-label="展开或收起 DAG 校验问题"
        @click="toggleValidationAlert"
      >
        <svg
          class="validation-chevron"
          width="10"
          height="10"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
        <span v-if="validationAlertExpanded" class="validation-title">DAG 校验问题</span>
        <span v-else class="validation-collapsed-line">{{ validationCollapsedLine }}</span>
      </button>
      <ul v-show="validationAlertExpanded" class="validation-list">
        <li v-for="(issue, idx) in page.validationIssues" :key="idx">{{ issue }}</li>
      </ul>
    </div>


    <WorkflowTemplateModal />

    <WorkflowStudioExpandLayer
      v-if="page.plan && page.selectedWorkflow"
      v-model:show="studioExpanded"
      v-model:props-open="propsPanelOpen"
      :title="page.selectedWorkflow.displayName"
      :plan="page.plan"
      :read-only="readOnly"
      :selected-node-id="page.selectedNodeId"
      :issue-node-ids="page.validationHighlightNodeIds"
      :fit-view-key="canvasFitViewKey"
      @update:plan="page.replacePlan"
      @select="onSelectNode"
    />

    <NSpin :show="page.detailLoading" class="detail-spin">
      <div v-if="page.plan" class="studio-body">
        <div class="studio-canvas">
          <WorkflowStudioCanvasToolbar :read-only="readOnly" />
          <WorkflowDagEditor
            :plan="page.plan"
            :read-only="readOnly"
            :selected-node-id="page.selectedNodeId"
            :issue-node-ids="page.validationHighlightNodeIds"
            :fit-view-key="canvasFitViewKey"
            :props-panel-open="propsPanelOpen"
            @update:plan="page.replacePlan"
            @select="onSelectNode"
          />
        </div>
        <WorkflowStudioPropsColumn v-model:open="propsPanelOpen" :show-expand-btn="false" />
      </div>
    </NSpin>
  </section>
</template>

<style scoped>
.detail-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  overflow: hidden;
}

.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
  padding: 12px 16px;
  border-bottom: 1px solid var(--sun-border);
}

.validation-alert {
  margin: 0;
  border-bottom: 1px solid var(--sun-border);
  border-radius: 0;
  background: var(--sun-amber-glow);
}

.validation-alert-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 16px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--sun-text);
}

.validation-alert.is-collapsed .validation-alert-head {
  padding: 8px 16px;
}

.validation-chevron {
  flex-shrink: 0;
  color: var(--sun-amber);
  opacity: 0.85;
  transition: transform 0.15s ease;
}

.validation-alert.is-expanded .validation-chevron {
  transform: rotate(90deg);
}

.validation-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.validation-collapsed-line {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.4;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.validation-list {
  margin: 0;
  padding: 0 16px 12px 38px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--sun-text-muted);
}

.detail-title-block h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
}

.detail-meta-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.detail-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono);
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
  margin-left: auto;
}

.publish-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
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
  width: min(200px, 36vw);
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
}

.more-menu-btn {
  width: 28px;
  height: 28px;
  padding: 0;
}

.detail-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.detail-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.studio-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  position: relative;
}

.studio-body :deep(.studio-props-splitter) {
  flex-shrink: 0;
}

.studio-canvas {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 10px 12px 12px;
}

.preview-block,
.nodes-block,
.props-block {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px;
  background: var(--sun-black);
}

.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-secondary);
  margin-bottom: 10px;
}

.field-label-row {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.io-input-block {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px 12px 2px;
  margin-bottom: 12px;
  background: var(--sun-black);
}

.io-block-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-muted);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

/* 业务节点配置 — 组间距由 node-props-form gap 统一 */
.node-props-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.node-props-form :deep(.wf-sec) {
  margin-top: 0;
}
.wf-param-label {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.wf-param-name {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
  background: none;
  padding: 0;
}
.wf-param-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.wf-param-hint {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.4;
}
.wf-mono-field :deep(.n-input__textarea-el),
.wf-mono-field :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  line-height: 1.5;
}
.required-mark {
  color: var(--sun-amber);
  font-size: var(--sun-font-sm);
  line-height: 1;
}

.field-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.node-chip-flow {
  margin-bottom: 4px;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.block-head .block-title {
  margin-bottom: 0;
}

.node-add-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(220px, 280px) 1fr;
  gap: 12px;
  min-height: 280px;
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.node-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text);
  cursor: pointer;
  text-align: left;
}

.node-chip.active {
  border-color: var(--sun-text);
  box-shadow: inset 0 0 0 1px var(--sun-text);
}

.node-chip-type {
  font-size: 11px;
  font-family: var(--sun-font-mono);
  color: var(--sun-text-muted);
  flex-shrink: 0;
}

.node-chip-label {
  flex: 1;
  font-size: 13px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-chip-del {
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  padding: 2px;
}

.node-chip-del:hover {
  color: var(--sun-danger, #e88080);
}

.node-empty,
.props-empty {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.node-chip-terminal {
  margin-top: 4px;
}

.props-form {
  margin-bottom: 0;
}

.node-props-form :deep(.n-form-item) {
  margin-bottom: 0;
}

.props-form :deep(.n-input),
.props-form :deep(.n-input-number .n-input) {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.props-form :deep(.n-input-number .n-input.n-input--disabled) {
  background-color: var(--sun-black) !important;
}

.props-form :deep(.n-base-selection) {
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
}

@media (max-width: 960px) {
  .editor-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
.version-select-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
}
</style>
