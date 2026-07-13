<script setup lang="ts">
import { computed, inject } from 'vue'
import { NButton, NIcon } from 'naive-ui'
import {
  AddOutline,
  ArrowRedoOutline,
  ArrowUndoOutline,
  AppsOutline,
  GitBranchOutline,
  RefreshOutline,
} from '@vicons/ionicons5'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import { addPlanGraphNode } from '../../utils/workflowDagLayout'
import type { WorkflowBusinessNodeType } from '../../utils/workflowPlan'
import type { WorkflowGatewayType } from '../../utils/workflowGateway'

defineProps<{
  readOnly?: boolean
}>()

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const showParallelBranch = computed(() => page.canEditPlan && page.isParallelWorkflow)

const businessPalette: { label: string; type: WorkflowBusinessNodeType }[] = [
  { label: 'RAG', type: 'rag' },
  { label: 'Tool', type: 'tool' },
  { label: 'Agent', type: 'agent' },
]

const gatewayPalette: { label: string; type: WorkflowGatewayType }[] = [
  { label: '并行分叉', type: 'parallel-gateway' },
  { label: '并行汇总', type: 'join' },
  { label: '条件分支', type: 'exclusive-gateway' },
]

function addNode(type: WorkflowBusinessNodeType | WorkflowGatewayType) {
  if (!page.plan || !page.canEditPlan) return
  const cx = 280 + Math.random() * 80
  const cy = 120 + Math.random() * 60
  page.replacePlan(addPlanGraphNode(page.plan, type, { x: cx, y: cy }))
}
</script>

<template>
  <div class="studio-canvas-toolbar">
    <div v-if="!readOnly" class="toolbar-group toolbar-palette">
      <NButton
        v-for="item in businessPalette"
        :key="item.type"
        size="tiny"
        round
        secondary
        @click="addNode(item.type)"
      >
        <template #icon><NIcon :component="AddOutline" :size="12" /></template>
        {{ item.label }}
      </NButton>
      <span class="toolbar-divider" aria-hidden="true" />
      <NButton
        v-for="item in gatewayPalette"
        :key="item.type"
        size="tiny"
        round
        secondary
        @click="addNode(item.type)"
      >
        <template #icon><NIcon :component="GitBranchOutline" :size="12" /></template>
        {{ item.label }}
      </NButton>
      <span v-if="showParallelBranch" class="toolbar-divider" aria-hidden="true" />
      <NButton
        v-if="showParallelBranch"
        size="tiny"
        round
        secondary
        title="在并行分叉后追加一条 RAG 分支"
        @click="page.addParallelBranch()"
      >
        <template #icon><NIcon :component="AddOutline" :size="12" /></template>
        RAG 分支
      </NButton>
    </div>

    <div class="toolbar-group toolbar-actions">
      <template v-if="!readOnly">
        <NButton
          size="tiny"
          round
          secondary
          title="撤销 (Ctrl+Z)"
          :disabled="!page.canUndo"
          @click="page.undo()"
        >
          <template #icon><NIcon :component="ArrowUndoOutline" :size="12" /></template>
        </NButton>
        <NButton
          size="tiny"
          round
          secondary
          title="重做 (Ctrl+Y)"
          :disabled="!page.canRedo"
          @click="page.redo()"
        >
          <template #icon><NIcon :component="ArrowRedoOutline" :size="12" /></template>
        </NButton>
        <span class="toolbar-divider" aria-hidden="true" />
        <NButton size="tiny" round secondary @click="page.openTemplateModal()">
          <template #icon><NIcon :component="AppsOutline" :size="12" /></template>
          流程模板
        </NButton>
        <NButton
          size="tiny"
          round
          secondary
          title="自动布局"
          @click="page.autoLayoutCurrentPlan()"
        >
          <template #icon><NIcon :component="RefreshOutline" :size="12" /></template>
          自动布局
        </NButton>
        <NButton
          size="tiny"
          round
          secondary
          :loading="page.validating"
          @click="void page.validatePlan()"
        >
          验证 DAG
        </NButton>
      </template>
    </div>
  </div>
</template>

<style scoped>
.studio-canvas-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  flex-shrink: 0;
  padding-bottom: 8px;
}

.toolbar-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.toolbar-actions {
  margin-left: auto;
}

.toolbar-divider {
  width: 1px;
  height: 18px;
  background: var(--sun-border);
  flex-shrink: 0;
}
</style>
