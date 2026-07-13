<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal, NRadio, NRadioGroup, NSpin } from 'naive-ui'
import WorkflowDagEditor from './WorkflowDagEditor.vue'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import type { WorkflowPlan } from '../../api/workflows'
import { normalizeWorkflowPlan } from '../../utils/workflowPlan'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const previewReady = ref(false)

const importTargetId = computed(() =>
  page.importMode === 'overwrite' ? page.selectedId : page.importDraft.id.trim(),
)

const importPlan = computed((): WorkflowPlan | null => {
  const raw = page.importPreviewBody?.plan
  const wfId = importTargetId.value
  if (!raw || typeof raw !== 'object' || !wfId) return null
  try {
    return normalizeWorkflowPlan(
      raw as WorkflowPlan,
      wfId,
      page.nodeDefaults ?? undefined,
    )
  } catch {
    return null
  }
})

watch(
  () => page.showImportModal,
  (open) => {
    previewReady.value = false
    if (open) {
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          previewReady.value = true
        })
      })
    }
  },
)

watch(
  () => [page.importMode, page.importDraft.id] as const,
  () => {
    if (!page.showImportModal) return
    void page.refreshImportValidation()
  },
)

function close() {
  page.closeImportModal()
}

function confirm() {
  void page.confirmImportPreview()
}
</script>

<template>
  <NModal
    :show="page.showImportModal"
    preset="card"
    class="wf-import-modal"
    title="导入 JSON 预览"
    :style="{ width: 'min(960px, 94vw)' }"
    :z-index="3300"
    to="body"
    :mask-closable="!page.importPreviewLoading"
    @update:show="v => { if (!v) close() }"
  >
    <NSpin :show="page.importPreviewLoading">
      <div class="wf-import-body">
        <aside class="wf-import-meta">
          <div class="import-mode">
            <p class="meta-section-label">导入方式</p>
            <NRadioGroup
              :value="page.importMode"
              size="small"
              @update:value="v => page.setImportMode(v as 'overwrite' | 'new')"
            >
              <NRadio
                v-if="page.selectedId"
                value="overwrite"
                label="覆盖当前工作流草稿"
              />
              <NRadio value="new" label="导入为新工作流" />
            </NRadioGroup>
          </div>

          <template v-if="page.importMode === 'new'">
            <NForm label-placement="top" size="small" class="import-form">
              <NFormItem label="Workflow ID" required>
                <NInput v-model:value="page.importDraft.id" class="sun-field" placeholder="如 my-report-flow" />
              </NFormItem>
              <NFormItem label="展示名" required>
                <NInput v-model:value="page.importDraft.displayName" class="sun-field" />
              </NFormItem>
              <NFormItem label="描述" required>
                <NInput
                  v-model:value="page.importDraft.description"
                  class="sun-field"
                  type="textarea"
                  :autosize="{ minRows: 2, maxRows: 3 }"
                />
              </NFormItem>
            </NForm>
          </template>
          <template v-else>
            <p class="meta-line">
              <span class="meta-label">目标工作流</span>
              #{{ page.selectedId }}
            </p>
            <p v-if="page.importPreviewBody?.displayName" class="meta-line">
              <span class="meta-label">包内展示名</span>
              {{ page.importPreviewBody.displayName }}
            </p>
          </template>

          <div v-if="page.importPreviewIssues.length" class="import-issues">
            <p class="issues-title">校验问题（{{ page.importPreviewIssues.length }}）</p>
            <ul>
              <li v-for="(issue, idx) in page.importPreviewIssues" :key="idx">{{ issue }}</li>
            </ul>
          </div>
          <p v-else-if="importPlan" class="import-ok">
            {{ page.importMode === 'new' ? 'Plan 校验通过，确认后将创建新工作流' : 'Plan 校验通过，确认后将写入当前工作流草稿' }}
          </p>
          <p v-else class="import-warn">无法解析 plan 字段</p>
        </aside>
        <div class="wf-import-canvas-wrap">
          <WorkflowDagEditor
            v-if="importPlan && previewReady"
            :key="`${importTargetId}-${previewReady}`"
            :plan="importPlan"
            read-only
          />
          <p v-else class="canvas-empty">无有效 Plan 预览</p>
        </div>
      </div>
    </NSpin>

    <template #footer>
      <div class="wf-import-footer">
        <NButton round secondary :disabled="page.importPreviewLoading" @click="close">取消</NButton>
        <NButton
          round
          type="primary"
          class="action-btn"
          :loading="page.importPreviewLoading"
          :disabled="!page.canConfirmImport"
          @click="confirm"
        >
          确认导入
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.wf-import-body {
  display: grid;
  grid-template-columns: minmax(240px, 280px) minmax(0, 1fr);
  gap: 16px;
  min-height: 420px;
}

.wf-import-meta {
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.import-mode {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.meta-section-label {
  margin: 0;
  font-size: 11px;
  color: var(--sun-text-muted);
}

.import-form {
  margin-top: 2px;
}

.meta-line {
  margin: 0;
  line-height: 1.45;
}

.meta-label {
  display: block;
  font-size: 11px;
  color: var(--sun-text-muted);
  margin-bottom: 2px;
}

.import-issues {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
}

.issues-title {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--sun-amber);
}

.import-issues ul {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  line-height: 1.45;
}

.import-ok {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.import-warn {
  margin: 0;
  font-size: 12px;
  color: var(--sun-amber);
}

.wf-import-canvas-wrap {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  min-height: 400px;
  display: flex;
  flex-direction: column;
}

.wf-import-canvas-wrap :deep(.wf-dag-editor) {
  flex: 1;
  min-height: 0;
  height: 100%;
}

.wf-import-canvas-wrap :deep(.wf-dag-canvas) {
  flex: 1;
  min-height: 380px;
  border: none;
  border-radius: 0;
}

.canvas-empty {
  margin: auto;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.wf-import-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 760px) {
  .wf-import-body {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
.wf-import-modal.n-card {
  background: var(--sun-black) !important;
}
</style>
