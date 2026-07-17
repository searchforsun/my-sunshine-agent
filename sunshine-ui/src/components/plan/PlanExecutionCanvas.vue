<script setup lang="ts">
/**
 * Chat / Plan 执行态只读 DAG：与 Studio 共用 workflowFlowProjection + WorkflowFlowNode，叠加 SSE 状态。
 * 禁止引入 workflowDagLayout 编辑 API（merge/add/autoLayout）或 WorkflowDagEditor。
 */
import { markRaw, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { VueFlow, type Node, type VueFlowStore } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import WorkflowFlowNode from '../workflows/WorkflowFlowNode.vue'
import type { PlanGraph } from '../../api/executionPlans'
import type { DagNodeView } from '../../utils/planGraph'
import { buildExecutionFlowElements, applyExecOverlay } from '../../utils/planExecutionCanvas'
import { WF_FLOW_NODE_TYPE, WF_FIT_VIEW_OPTS, type WorkflowFlowNodeData } from '../../utils/workflowFlowProjection'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'

const props = defineProps<{
  graph: PlanGraph
  dagNodes: DagNodeView[]
  selectedId?: string
  live?: boolean
  fluid?: boolean
  showExpand?: boolean
  loadingLabel?: string
}>()

const emit = defineEmits<{
  select: [node: DagNodeView]
  expand: []
}>()

const nodeTypes = { [WF_FLOW_NODE_TYPE]: markRaw(WorkflowFlowNode) } as import('@vue-flow/core').NodeTypesObject

const defaultEdgeOptions = {
  type: 'smoothstep',
  style: { stroke: '#737373', strokeWidth: 2 },
  selectable: false,
  focusable: false,
}

const nodes = ref<Node<WorkflowFlowNodeData>[]>([])
const edges = ref<import('@vue-flow/core').Edge[]>([])
const canvasReady = ref(false)
const canvasRef = ref<HTMLElement | null>(null)
const flowStore = ref<VueFlowStore | null>(null)
const { state: drawerState, drawerWidth } = usePlanNodeDrawer()
let resizeObserver: ResizeObserver | null = null
let resizeFitTimer: ReturnType<typeof setTimeout> | null = null
let lastFluidFitKey = ''

function dagNodeById(id: string): DagNodeView | undefined {
  return props.dagNodes.find(n => n.id === id)
}

function graphStructureKey(): string {
  const g = props.graph
  return JSON.stringify({
    nodes: (g?.nodes ?? []).map(n => ({ id: n.id, type: n.type, displayName: n.displayName })),
    edges: (g?.edges ?? []).map(e => ({ from: e.from, to: e.to })),
    layout: g?.layout ?? {},
  })
}

function hydrateStructure() {
  if (!props.graph?.nodes?.length) {
    canvasReady.value = false
    nodes.value = []
    edges.value = []
    return
  }
  const { nodes: n, edges: e } = buildExecutionFlowElements(props.graph, props.dagNodes, {
    selectedId: props.selectedId,
    live: props.live,
  })
  nodes.value = n
  edges.value = e
  canvasReady.value = n.length > 0
  void nextTick(() => fitViewSoon())
}

function refreshOverlay() {
  if (!nodes.value.length) return
  // 经 unknown 打断 Vue Flow Node 泛型在 vue-tsc 下的过深实例化
  const next = applyExecOverlay(
    nodes.value as unknown as Node<WorkflowFlowNodeData>[],
    props.dagNodes,
    props.live,
    props.selectedId,
  )
  nodes.value = next as unknown as Node<WorkflowFlowNodeData>[]
}

function dagNodesOverlayKey(): string {
  return props.dagNodes.map(n => [
    n.id,
    n.status,
    n.durationMs ?? '',
    n.attemptCount ?? '',
    n.recoveryAwaiting ? '1' : '0',
  ].join(':')).join('|')
}

function fitViewSoon(delayMs = 0) {
  if (!flowStore.value || !canvasReady.value) return
  const run = () => {
    requestAnimationFrame(() => {
      void flowStore.value?.fitView({
        ...WF_FIT_VIEW_OPTS,
        padding: props.fluid ? 0.18 : WF_FIT_VIEW_OPTS.padding,
      })
    })
  }
  if (delayMs > 0) setTimeout(run, delayMs)
  else run()
}

function scheduleFitViewOnResize() {
  if (!canvasReady.value) return
  if (resizeFitTimer) clearTimeout(resizeFitTimer)
  resizeFitTimer = setTimeout(() => {
    resizeFitTimer = null
    fitViewSoon()
  }, props.fluid ? 220 : 80)
}

function bindResizeObserver(el: HTMLElement | null) {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (!el || typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver(() => scheduleFitViewOnResize())
  resizeObserver.observe(el)
}

function onFlowInit(store: VueFlowStore) {
  flowStore.value = store
  fitViewSoon()
}

function onNodeClick(payload: { node: Node }) {
  const dag = dagNodeById(payload.node.id)
  if (dag) emit('select', dag)
}

watch(
  () => graphStructureKey(),
  () => hydrateStructure(),
  { immediate: true },
)

watch(
  () => dagNodesOverlayKey(),
  () => refreshOverlay(),
  { immediate: true },
)

watch(
  () => props.selectedId,
  () => refreshOverlay(),
)

/** 节点抽屉开合/改宽会挤占左侧画布，需重新 fit（放大态节流，避免切换节点卡顿） */
watch(
  () => [drawerState.open, drawerWidth.value, props.fluid] as const,
  ([open, width, fluid]) => {
    if (!fluid) return
    const key = `${open}:${width}`
    if (key === lastFluidFitKey) return
    lastFluidFitKey = key
    fitViewSoon(180)
  },
)

watch(canvasRef, (el) => bindResizeObserver(el))

onMounted(() => {
  hydrateStructure()
  bindResizeObserver(canvasRef.value)
})

onUnmounted(() => {
  if (resizeFitTimer) clearTimeout(resizeFitTimer)
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<template>
  <div
    v-if="canvasReady"
    ref="canvasRef"
    class="plan-exec-canvas"
    :class="{ 'is-fluid': fluid, 'is-loading': loadingLabel, 'has-expand-btn': showExpand && !fluid }"
  >
    <button
      v-if="showExpand && !fluid"
      type="button"
      class="plan-exec-expand-btn"
      title="放大查看"
      aria-label="放大执行图"
      @click.stop="emit('expand')"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3" />
      </svg>
    </button>
    <div v-if="loadingLabel" class="plan-exec-overlay" role="status" aria-live="polite">
      <span class="plan-exec-spinner" aria-hidden="true" />
      <span>{{ loadingLabel }}</span>
    </div>
    <VueFlow
      v-model:nodes="nodes"
      v-model:edges="edges"
      :node-types="nodeTypes"
      :default-edge-options="defaultEdgeOptions"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="true"
      :edges-focusable="false"
      :pan-on-drag="fluid"
      :zoom-on-scroll="fluid"
      :zoom-on-pinch="fluid"
      :zoom-on-double-click="false"
      :min-zoom="0.25"
      :max-zoom="1.75"
      class="plan-exec-flow"
      @init="onFlowInit"
      @node-click="onNodeClick"
    >
      <Background :gap="16" :size="2" pattern-color="var(--sun-dag-dot, var(--sun-border-light))" />
    </VueFlow>
  </div>
</template>

<style scoped>
.plan-exec-canvas {
  position: relative;
  margin: 8px 0 4px calc(var(--op-gutter, 12px) + 4px);
  min-height: 200px;
  height: 200px;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: var(--sun-black);
  overflow: hidden;
}

.plan-exec-canvas.has-expand-btn {
  padding-right: 0;
}

.plan-exec-canvas.is-fluid {
  margin: 0;
  border: none;
  border-radius: 0;
  min-height: 360px;
  height: 100%;
  flex: 1;
}

.plan-exec-canvas.is-loading :deep(.vue-flow) {
  visibility: hidden;
}

.plan-exec-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.plan-exec-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid color-mix(in srgb, var(--sun-text-muted) 28%, transparent);
  border-top-color: var(--sun-text-muted);
  border-radius: 50%;
  animation: plan-exec-spin 0.75s linear infinite;
}

@keyframes plan-exec-spin {
  to { transform: rotate(360deg); }
}

.plan-exec-expand-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 6;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--sun-border);
  border-radius: 6px;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  cursor: pointer;
}

.plan-exec-expand-btn:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

.plan-exec-flow {
  width: 100%;
  height: 100%;
}

.plan-exec-canvas :deep(.vue-flow__edge-path),
.plan-exec-canvas :deep(.vue-flow__connection-path) {
  stroke: #737373;
  stroke-width: 2;
}

.plan-exec-canvas :deep(.vue-flow__background circle) {
  fill: var(--sun-dag-dot, var(--sun-border-light));
}
</style>
