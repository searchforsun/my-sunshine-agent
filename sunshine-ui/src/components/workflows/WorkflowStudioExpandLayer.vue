<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref, watch } from 'vue'
import { NButton, NIcon } from 'naive-ui'
import { CloseOutline } from '@vicons/ionicons5'
import WorkflowDagEditor from './WorkflowDagEditor.vue'
import WorkflowStudioCanvasToolbar from './WorkflowStudioCanvasToolbar.vue'
import WorkflowStudioPropsColumn from './WorkflowStudioPropsColumn.vue'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import type { WorkflowPlan } from '../../api/workflows'

const props = defineProps<{
  show: boolean
  title: string
  plan: WorkflowPlan
  readOnly?: boolean
  selectedNodeId?: string | null
  propsOpen?: boolean
  fitViewKey?: string | null
  issueNodeIds?: Set<string>
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  'update:propsOpen': [value: boolean]
  'update:plan': [plan: WorkflowPlan]
  select: [nodeId: string | null]
}>()

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const validationAlertExpanded = ref(false)

const validationCollapsedLine = computed(() => {
  const issues = page.validationIssues
  if (issues.length === 0) return ''
  const head = `DAG 校验问题（${issues.length}）`
  return issues.length === 1 ? `${head} · ${issues[0]}` : `${head} · ${issues[0]}…`
})

watch(
  () => page.validationIssues.length,
  (count, prev) => {
    if (count > 0 && count !== prev) {
      validationAlertExpanded.value = false
    }
  },
)

function close() {
  emit('update:show', false)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.show) {
    e.preventDefault()
    close()
  }
}

function onSelectNode(nodeId: string | null) {
  emit('select', nodeId)
  emit('update:propsOpen', true)
}

watch(
  () => props.show,
  (open) => {
    if (open) {
      document.body.classList.add('wf-studio-layer-open')
      document.body.style.overflow = 'hidden'
      emit('update:propsOpen', true)
    } else {
      document.body.classList.remove('wf-studio-layer-open')
      document.body.style.overflow = ''
    }
  },
  { immediate: true },
)

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.classList.remove('wf-studio-layer-open')
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <div v-if="show" class="wf-studio-layer" role="dialog" aria-modal="true" aria-label="工作流全屏画布">
      <header class="wf-studio-layer-head">
        <div class="wf-studio-layer-title">
          <span class="wf-studio-layer-label">工作流画布</span>
          <span class="wf-studio-layer-name">{{ title }}</span>
        </div>
        <NButton quaternary circle title="退出全屏 (Esc)" @click="close">
          <template #icon><NIcon :component="CloseOutline" :size="18" /></template>
        </NButton>
      </header>
      <div class="wf-studio-layer-toolbar">
        <WorkflowStudioCanvasToolbar :read-only="readOnly" />
      </div>
      <div
        v-if="page.validationIssues.length"
        class="wf-studio-layer-validation"
        :class="{ 'is-collapsed': !validationAlertExpanded, 'is-expanded': validationAlertExpanded }"
      >
        <button
          type="button"
          class="wf-studio-layer-validation-head"
          :aria-expanded="validationAlertExpanded"
          aria-label="展开或收起 DAG 校验问题"
          @click="validationAlertExpanded = !validationAlertExpanded"
        >
          <svg
            class="wf-studio-layer-validation-chevron"
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
          <span v-if="validationAlertExpanded" class="wf-studio-layer-validation-title">DAG 校验问题</span>
          <span v-else class="wf-studio-layer-validation-collapsed">{{ validationCollapsedLine }}</span>
        </button>
        <ul v-show="validationAlertExpanded" class="wf-studio-layer-validation-list">
          <li v-for="(issue, idx) in page.validationIssues" :key="idx">{{ issue }}</li>
        </ul>
      </div>
      <div class="wf-studio-layer-body studio-body">
        <div class="studio-canvas">
          <WorkflowDagEditor
            :plan="plan"
            :read-only="readOnly"
            :selected-node-id="selectedNodeId"
            :issue-node-ids="issueNodeIds"
            :fit-view-key="fitViewKey"
            :props-panel-open="propsOpen"
            fullscreen
            @update:plan="emit('update:plan', $event)"
            @select="onSelectNode"
          />
        </div>
        <WorkflowStudioPropsColumn
          :open="propsOpen ?? true"
          :show-expand-btn="false"
          @update:open="emit('update:propsOpen', $event)"
        />
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.wf-studio-layer {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  background: var(--sun-black);
}

.wf-studio-layer-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--sun-border);
}

.wf-studio-layer-toolbar {
  flex-shrink: 0;
  padding: 8px 16px 0;
  border-bottom: 1px solid var(--sun-border);
}

.wf-studio-layer-toolbar :deep(.studio-canvas-toolbar) {
  padding-bottom: 8px;
}

.wf-studio-layer-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.wf-studio-layer-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text-secondary);
  flex-shrink: 0;
}

.wf-studio-layer-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--sun-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wf-studio-layer-validation {
  flex-shrink: 0;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-amber-glow);
}
.wf-studio-layer-validation-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 16px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--sun-text);
}
.wf-studio-layer-validation.is-collapsed .wf-studio-layer-validation-head {
  padding: 6px 16px;
}
.wf-studio-layer-validation-chevron {
  flex-shrink: 0;
  color: var(--sun-amber);
  opacity: 0.85;
  transition: transform 0.15s ease;
}
.wf-studio-layer-validation.is-expanded .wf-studio-layer-validation-chevron {
  transform: rotate(90deg);
}
.wf-studio-layer-validation-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}
.wf-studio-layer-validation-collapsed {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.4;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wf-studio-layer-validation-list {
  margin: 0;
  padding: 0 16px 10px 38px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--sun-text-muted);
  max-height: 200px;
  overflow-y: auto;
}

.wf-studio-layer-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  position: relative;
}

.wf-studio-layer-body :deep(.studio-props-splitter) {
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

.wf-studio-layer-body :deep(.wf-dag-editor) {
  flex: 1;
  min-height: 0;
}

.wf-studio-layer-body :deep(.wf-dag-canvas) {
  flex: 1;
  min-height: 0;
  height: auto;
}
</style>

<style>
body.wf-studio-layer-open .n-base-select-menu,
body.wf-studio-layer-open .v-binder-follower-container {
  z-index: 3200 !important;
}
</style>
