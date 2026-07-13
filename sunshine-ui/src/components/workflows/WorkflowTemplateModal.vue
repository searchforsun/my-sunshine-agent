<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { NButton, NModal } from 'naive-ui'
import WorkflowDagEditor from './WorkflowDagEditor.vue'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import {
  WORKFLOW_TEMPLATES,
  buildWorkflowTemplatePreviewPlan,
  type WorkflowTemplateDefinition,
  type WorkflowTemplateId,
} from '../../utils/workflowTemplates'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const selectedId = ref<WorkflowTemplateId | null>(WORKFLOW_TEMPLATES[0]?.id ?? null)
const previewReady = ref(false)

const templateCtx = computed(() => ({
  isParallel: page.isParallelWorkflow,
}))

const selectedTemplate = computed(() =>
  WORKFLOW_TEMPLATES.find(t => t.id === selectedId.value) ?? null,
)

const previewPlan = computed(() => {
  if (!selectedId.value || !previewReady.value) return null
  try {
    return buildWorkflowTemplatePreviewPlan(selectedId.value, page.nodeDefaults)
  } catch {
    return null
  }
})

const selectedDisabledReason = computed(() => {
  const tpl = selectedTemplate.value
  if (!tpl || !page.canEditPlan) return page.canEditPlan ? undefined : '只读版本不可应用模板'
  return tpl.disabledReason?.(templateCtx.value)
})

watch(
  () => page.showTemplateModal,
  (open) => {
    if (open) {
      selectedId.value = WORKFLOW_TEMPLATES.find(t => !t.disabledReason?.(templateCtx.value))?.id
        ?? WORKFLOW_TEMPLATES[0]?.id
        ?? null
      previewReady.value = false
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          previewReady.value = true
        })
      })
    } else {
      previewReady.value = false
    }
  },
)

watch(selectedId, () => {
  if (!page.showTemplateModal) return
  previewReady.value = false
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      previewReady.value = true
    })
  })
})

function close() {
  page.showTemplateModal = false
}

function templateDisabled(tpl: WorkflowTemplateDefinition): string | undefined {
  if (!page.canEditPlan) return '只读版本不可应用'
  return tpl.disabledReason?.(templateCtx.value)
}

function applySelected() {
  if (!selectedId.value || selectedDisabledReason.value) return
  page.applyWorkflowTemplate(selectedId.value)
  close()
}
</script>

<template>
  <NModal
    :show="page.showTemplateModal"
    preset="card"
    class="wf-template-modal"
    title="流程模板"
    :style="{ width: 'min(960px, 94vw)' }"
    :z-index="3300"
    to="body"
    :mask-closable="true"
    @update:show="v => { page.showTemplateModal = v }"
  >
    <div class="wf-template-body">
      <ul class="wf-template-list">
        <li
          v-for="tpl in WORKFLOW_TEMPLATES"
          :key="tpl.id"
          class="wf-template-item"
          :class="{ active: selectedId === tpl.id, disabled: !!templateDisabled(tpl) }"
          @click="selectedId = tpl.id"
        >
          <div class="wf-template-item-head">
            <span class="wf-template-item-name">{{ tpl.name }}</span>
          </div>
          <p class="wf-template-item-summary">{{ tpl.summary }}</p>
          <p v-if="templateDisabled(tpl)" class="wf-template-item-hint">{{ templateDisabled(tpl) }}</p>
        </li>
      </ul>

      <aside v-if="selectedTemplate" class="wf-template-preview">
        <div class="wf-template-canvas-wrap">
          <WorkflowDagEditor
            v-if="previewPlan"
            :key="`${selectedId ?? ''}-${previewReady}`"
            :plan="previewPlan"
            read-only
          />
        </div>
        <p v-if="selectedDisabledReason" class="wf-template-preview-warn">{{ selectedDisabledReason }}</p>
      </aside>
    </div>

    <template #footer>
      <div class="wf-template-footer">
        <NButton round secondary @click="close">取消</NButton>
        <NButton
          round
          type="primary"
          class="action-btn"
          :disabled="!selectedId || !!selectedDisabledReason"
          @click="applySelected"
        >
          应用模板
        </NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.wf-template-body {
  display: grid;
  grid-template-columns: minmax(240px, 280px) minmax(0, 1fr);
  gap: 16px;
  min-height: 480px;
}

.wf-template-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  max-height: 520px;
}

.wf-template-item {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  cursor: pointer;
  background: var(--sun-black);
  transition: border-color 0.15s;
}

.wf-template-item:hover:not(.disabled) {
  border-color: var(--sun-border-light);
}

.wf-template-item.active {
  border-color: var(--sun-text-muted);
  box-shadow: inset 0 0 0 1px var(--sun-text-muted);
}

.wf-template-item.disabled {
  opacity: 0.55;
  cursor: default;
}

.wf-template-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.wf-template-item-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.wf-template-item-summary {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  line-height: 1.45;
}

.wf-template-item-hint {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--sun-amber);
}

.wf-template-preview {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px;
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  min-height: 0;
}

.wf-template-canvas-wrap {
  flex: 1;
  min-height: 400px;
  display: flex;
  flex-direction: column;
}

.wf-template-canvas-wrap :deep(.wf-dag-editor) {
  flex: 1;
  min-height: 0;
  height: 100%;
}

.wf-template-canvas-wrap :deep(.wf-dag-canvas) {
  flex: 1;
  min-height: 380px;
  height: 100%;
  border: none;
  border-radius: 0;
}

.wf-template-preview-warn {
  margin: 0;
  flex-shrink: 0;
  font-size: 11px;
  color: var(--sun-amber);
}

.wf-template-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 760px) {
  .wf-template-body {
    grid-template-columns: 1fr;
    min-height: auto;
  }

  .wf-template-list {
    max-height: 180px;
  }

  .wf-template-canvas-wrap {
    min-height: 300px;
  }
}
</style>

<style>
.wf-template-modal.n-card {
  background: var(--sun-black) !important;
}

.wf-template-modal.n-card > .n-card__content {
  padding-top: 8px;
}
</style>
