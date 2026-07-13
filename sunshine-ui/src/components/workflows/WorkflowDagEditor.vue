<script setup lang="ts">
/**
 * Plan 为 SSOT；Vue Flow 仅在 hydrate 完成后挂载，避免 init 时 edges-change reset 清空连线。
 */
import { computed, markRaw, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import {
  VueFlow,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
  type VueFlowStore,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import WorkflowFlowNode from './WorkflowFlowNode.vue'
import type { WorkflowPlan } from '../../api/workflows'
import {
  WF_FLOW_NODE_TYPE,
  isProtectedWorkflowNode,
  evaluateConnection,
  mergeFlowIntoPlan,
  planToFlowElements,
  resolveNodePositions,
  type WorkflowFlowNodeData,
} from '../../utils/workflowDagLayout'
import { FLOW_CONFIG_SELECTION } from '../../utils/workflowPlan'

const props = defineProps<{
  plan: WorkflowPlan
  readOnly?: boolean
  selectedNodeId?: string | null
  fullscreen?: boolean
  fitViewKey?: string | null
  /** 右侧属性面板展开态，变化时重新居中画布 */
  propsPanelOpen?: boolean
  issueNodeIds?: Set<string>
}>()

const emit = defineEmits<{
  'update:plan': [plan: WorkflowPlan]
  select: [nodeId: string | null]
}>()

const message = useMessage()
const nodeTypes = { [WF_FLOW_NODE_TYPE]: markRaw(WorkflowFlowNode) } as import('@vue-flow/core').NodeTypesObject

const defaultEdgeOptions = {
  type: 'smoothstep',
  style: { stroke: '#737373', strokeWidth: 2 },
}

const nodes = ref<Node<WorkflowFlowNodeData>[]>([])
const edges = ref<Edge[]>([])
const canvasReady = ref(false)
const canvasRef = ref<HTMLElement | null>(null)
const pushingToPlan = ref(false)
let hydrating = false
let flowStore: VueFlowStore | null = null
let pendingInvalidReason: string | null = null
let resizeObserver: ResizeObserver | null = null
let resizeFitTimer: ReturnType<typeof setTimeout> | null = null

const planGraphKey = computed(() => JSON.stringify({
  nodes: (props.plan.nodes ?? []).map(n => ({ id: n.id, type: n.type, displayName: n.displayName })),
  edges: props.plan.edges ?? [],
}))

const layoutKey = computed(() => JSON.stringify(props.plan.layout ?? {}))

async function hydrateFromPlan() {
  hydrating = true
  canvasReady.value = false
  const { nodes: n, edges: e } = planToFlowElements(
    props.plan,
    props.selectedNodeId,
    props.readOnly,
    props.issueNodeIds,
  )
  nodes.value = n
  edges.value = e
  await nextTick()
  canvasReady.value = n.length > 0
  await nextTick()
  requestAnimationFrame(() => {
    hydrating = false
    fitViewSoon()
  })
}

function patchNodePresentation() {
  if (nodes.value.length === 0) return
  const selectedId = props.selectedNodeId
  const readOnly = !!props.readOnly
  const issueIds = props.issueNodeIds
  for (const node of nodes.value) {
    const data = node.data
    if (!data) continue
    data.selected = selectedId === node.id
    data.readOnly = readOnly
    data.hasValidationIssue = issueIds?.has(node.id) ?? false
  }
}

function syncLayoutFromPlan() {
  if (pushingToPlan.value || hydrating || nodes.value.length === 0) return
  const positions = resolveNodePositions(props.plan)
  let moved = false
  nodes.value = nodes.value.map(node => {
    const next = positions[node.id] ?? node.position
    if (Math.abs(next.x - node.position.x) > 1 || Math.abs(next.y - node.position.y) > 1) {
      moved = true
    }
    return { ...node, position: next }
  })
  if (moved) fitViewSoon()
}

function fitViewSoon(delayMs = 0) {
  if (!flowStore || !canvasReady.value) return
  void nextTick(() => {
    const run = () => {
      requestAnimationFrame(() => {
        void flowStore?.fitView({ padding: 0.22, duration: 0 })
      })
    }
    if (delayMs > 0) setTimeout(run, delayMs)
    else run()
  })
}

function scheduleFitViewOnResize() {
  if (hydrating || !canvasReady.value) return
  if (resizeFitTimer) clearTimeout(resizeFitTimer)
  resizeFitTimer = setTimeout(() => {
    resizeFitTimer = null
    fitViewSoon()
  }, props.fullscreen ? 160 : 120)
}

function pushPlan(next: WorkflowPlan) {
  pushingToPlan.value = true
  emit('update:plan', next)
  void nextTick(() => {
    pushingToPlan.value = false
  })
}

function emitFromFlow() {
  if (props.readOnly) return
  pushPlan(mergeFlowIntoPlan(props.plan, nodes.value, edges.value))
}

function syncExternalPlan() {
  if (pushingToPlan.value) return
  void hydrateFromPlan()
}

watch(
  () => [props.fitViewKey, planGraphKey.value] as const,
  () => syncExternalPlan(),
  { immediate: true },
)

watch(layoutKey, () => syncLayoutFromPlan())

watch(
  () => [props.selectedNodeId, props.readOnly, props.issueNodeIds] as const,
  () => patchNodePresentation(),
  { deep: true },
)

watch(
  () => props.fullscreen,
  (isFs) => {
    if (isFs) fitViewSoon(200)
  },
)

watch(
  () => props.propsPanelOpen,
  () => fitViewSoon(props.fullscreen ? 180 : 120),
)

function onFlowInit(store: VueFlowStore) {
  flowStore = store
  fitViewSoon(props.fullscreen ? 200 : 80)
}

onMounted(() => {
  if (!canvasRef.value || typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver(() => scheduleFitViewOnResize())
  resizeObserver.observe(canvasRef.value)
})

onUnmounted(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (resizeFitTimer) clearTimeout(resizeFitTimer)
})

function onNodesChange(changes: NodeChange[]) {
  if (hydrating) return
  const safeChanges = changes.filter(change => {
    if (change.type !== 'remove') return true
    const node = nodes.value.find(n => n.id === change.id)
    if (!node) return true
    return !isProtectedWorkflowNode({ id: node.id, type: node.data?.nodeType ?? '' })
  })
  nodes.value = applyNodeChanges(safeChanges, nodes.value as import('@vue-flow/core').GraphNode[]) as Node<WorkflowFlowNodeData>[]
  if (props.readOnly) return
  if (safeChanges.some(c => c.type === 'remove')) {
    emitFromFlow()
  } else if (safeChanges.some(c => c.type === 'position' && c.dragging === false)) {
    emitFromFlow()
  }
}

function onEdgesChange(changes: EdgeChange[]) {
  if (hydrating) return
  // Vue Flow mount 时会 emit reset/add，与外部 hydrate 冲突导致边被清空
  if (!changes.some(c => c.type === 'remove')) return
  edges.value = applyEdgeChanges(changes, edges.value as import('@vue-flow/core').GraphEdge[])
  if (props.readOnly || pushingToPlan.value) return
  emitFromFlow()
}

function checkValidConnection(connection: Connection) {
  if (!connection.source || !connection.target) return false
  const result = evaluateConnection(connection, props.plan)
  pendingInvalidReason = result.ok ? null : result.reason
  return result.ok
}

function onConnectEnd() {
  if (props.readOnly || !pendingInvalidReason) return
  message.warning(pendingInvalidReason)
  pendingInvalidReason = null
}

function onConnect(connection: Connection) {
  if (props.readOnly) return
  pendingInvalidReason = null
  const result = evaluateConnection(connection, props.plan)
  if (!result.ok) return
  const id = `${connection.source}->${connection.target}`
  if (edges.value.some(e => e.id === id)) return
  edges.value = [
    ...edges.value,
    {
      id,
      source: connection.source!,
      target: connection.target!,
      type: 'smoothstep',
    },
  ]
  emitFromFlow()
}

function onNodeClick(payload: { node: Node }) {
  emit('select', payload.node.id === 'start' ? FLOW_CONFIG_SELECTION : payload.node.id)
}

function onPaneClick() {
  emit('select', FLOW_CONFIG_SELECTION)
}
</script>

<template>
  <div class="wf-dag-editor" :class="{ 'is-fullscreen': fullscreen }">
    <div ref="canvasRef" class="wf-dag-canvas">
      <VueFlow
        v-if="canvasReady"
        :key="fitViewKey ?? 'wf-canvas'"
        v-model:nodes="nodes"
        v-model:edges="edges"
        :node-types="nodeTypes"
        :default-edge-options="defaultEdgeOptions"
        :fit-view-on-init="true"
        :nodes-draggable="!readOnly"
        :nodes-connectable="!readOnly"
        :elements-selectable="true"
        :delete-key-code="readOnly ? null : ['Backspace', 'Delete']"
        :is-valid-connection="checkValidConnection"
        :min-zoom="0.25"
        :max-zoom="1.75"
        @init="onFlowInit"
        @nodes-change="onNodesChange"
        @edges-change="onEdgesChange"
        @connect="onConnect"
        @connect-end="onConnectEnd"
        @node-click="onNodeClick"
        @pane-click="onPaneClick"
      >
        <Background :gap="16" :size="2" pattern-color="var(--sun-dag-dot, var(--sun-border-light))" />
        <Controls :show-interactive="false" position="bottom-right" />
      </VueFlow>
      <div v-else class="wf-dag-empty">
        <p v-if="nodes.length === 0">画布无节点</p>
        <p v-else class="wf-dag-empty-sub">加载画布…</p>
      </div>
    </div>
    <p v-if="!readOnly && !fullscreen" class="wf-dag-hint">
      拖拽移动 · 圆点连线 · Delete 删除业务节点 · 开始/结束不可删 · 非法连线会提示原因
    </p>
  </div>
</template>

<style scoped>
.wf-dag-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
  height: 100%;
}

.wf-dag-canvas {
  position: relative;
  flex: 1;
  min-height: 280px;
  min-width: 0;
  height: 100%;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--sun-black);
}

.wf-dag-canvas :deep(.vue-flow__edge-path),
.wf-dag-canvas :deep(.vue-flow__connection-path) {
  stroke: #737373;
  stroke-width: 2;
}

.wf-dag-canvas :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: var(--sun-blue, #58a6ff);
}

.wf-dag-canvas :deep(.vue-flow) {
  width: 100%;
  height: 100%;
}

.wf-dag-canvas :deep(.vue-flow__background circle) {
  fill: var(--sun-dag-dot);
}

.wf-dag-canvas :deep(.vue-flow__controls) {
  border: 1px solid var(--sun-border);
  border-radius: 6px;
  overflow: hidden;
  box-shadow: none;
}

.wf-dag-canvas :deep(.vue-flow__controls-button) {
  background: var(--sun-black);
  border-bottom: 1px solid var(--sun-border);
  fill: var(--sun-text-secondary);
}

.wf-dag-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--sun-text-muted);
  font-size: 13px;
}

.wf-dag-empty-sub {
  margin: 0;
  font-size: 12px;
}

.wf-dag-hint {
  margin: 0;
  flex-shrink: 0;
  font-size: 11px;
  color: var(--sun-text-muted);
  line-height: 1.4;
}

.is-fullscreen .wf-dag-canvas {
  border: none;
  border-radius: 0;
  min-height: 0;
}
</style>
