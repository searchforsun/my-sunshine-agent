<script setup lang="ts">
import { computed, inject, unref } from 'vue'
import { NButton, NIcon, useMessage } from 'naive-ui'
import {
  AddOutline,
  ArrowRedoOutline,
  ArrowUndoOutline,
  AppsOutline,
  GitBranchOutline,
  RefreshOutline,
} from '@vicons/ionicons5'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import { addPlanGraphNode, resolveLoopParentForAdd } from '../../utils/workflowDagLayout'
import type { WorkflowBusinessNodeType } from '../../utils/workflowPlan'
import { isGatewayType, type WorkflowGatewayType } from '../../utils/workflowGateway'

defineProps<{
  readOnly?: boolean
}>()

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi
const message = useMessage()

const showParallelBranch = computed(() => page.canEditPlan && page.isParallelWorkflow)

const businessPalette: { label: string; type: WorkflowBusinessNodeType }[] = [
  { label: 'RAG', type: 'rag' },
  { label: 'Tool', type: 'tool' },
  { label: 'Agent', type: 'agent' },
]

const gatewayPalette: { label: string; type: WorkflowGatewayType | 'loop' }[] = [
  { label: '并行分叉', type: 'parallel-gateway' },
  { label: '并行汇总', type: 'join' },
  { label: '条件分支', type: 'exclusive-gateway' },
  { label: '循环', type: 'loop' },
]

function addNode(type: WorkflowBusinessNodeType | WorkflowGatewayType | 'loop') {
  if (!page.plan || !page.canEditPlan) return
  const parentLoopId = resolveLoopParentForAdd(page.plan, unref(page.selectedNodeId as never))
  if (parentLoopId && (type === 'loop' || isGatewayType(type))) {
    message.warning('循环框内仅可添加 RAG / Tool / Agent')
    return
  }
  const prevIds = new Set((page.plan.nodes ?? []).map(n => n.id))
  const cx = 280 + Math.random() * 80
  const cy = 120 + Math.random() * 60
  const next = addPlanGraphNode(
    page.plan,
    type,
    { x: cx, y: cy },
    page.nodeDefaults,
    { selectedNodeId: unref(page.selectedNodeId as never) },
  )
  page.replacePlan(next)
  if (type === 'loop') {
    const loop = (next.nodes ?? []).find(n => !prevIds.has(n.id) && n.type === 'loop')
    if (loop) page.selectedNodeId = loop.id
    return
  }
  const added = (next.nodes ?? []).find(n => !prevIds.has(n.id) && n.type === type)
  if (added) page.selectedNodeId = added.id
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
