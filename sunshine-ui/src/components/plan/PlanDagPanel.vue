<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  formatStepLabel,
  resolvePlanIdFromStep,
  resolveStepDurationMs,
  stepLifecycle,
} from '../../api/processingSteps'
import { getExecutionPlan, type ExecutionPlanDetail, type PlanGraph } from '../../api/executionPlans'
import { listSkillCatalogIndex, type SkillCatalogIndexEntry } from '../../api/skills'
import { buildDagNodes, resolveDagNodeStep, type DagNodeView } from '../../utils/planGraph'
import { type HitlConfirmationPayload } from '../../api/hitlSteps'
import { listPlanDagNodeSteps } from '../../api/planHydrate'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'
import { usePlanDagExpand, unregisterPlanDagSelectHandler, registerPlanDagSelectHandler } from '../../composables/usePlanDagExpand'
import PlanExecutionCanvas from './PlanExecutionCanvas.vue'

const props = defineProps<{
  planStep: ProcessingStep
  allSteps: ProcessingStep[]
  live?: boolean
  executionPlanId?: string
  userQuery?: string
  pendingHitlConfirmations?: HitlConfirmationPayload | HitlConfirmationPayload[]
}>()

const { open: openPlanNodeDrawer, state: drawerState, isActivePlan } = usePlanNodeDrawer()
const { open: openExpand, close: closeExpand, isExpanded, update: updateExpand, bindSelect, state: expandState } = usePlanDagExpand()

function subStepsSignature(steps?: ProcessingStep[]): string {
  if (!steps?.length) return ''
  return steps.map(s => [
    s.id,
    s.lifecycle ?? '',
    s.summary?.after ?? '',
    s.summary?.active ?? '',
    s.metadata?.hitlStatus ?? '',
    s.metadata?.hitlToken ?? '',
    s.metadata?.hitlParamsSummary ?? '',
    s.reasoning?.length ?? 0,
    s.result?.length ?? 0,
    s.detail?.length ?? 0,
  ].join(':')).join('\u0002')
}

function stepContentSignature(step?: ProcessingStep): string {
  if (!step) return ''
  return [
    step.lifecycle ?? '',
    step.result ?? '',
    step.detail ?? '',
    step.reasoning ?? '',
    step.metadata?.rewriteApplied ? '1' : '0',
    step.metadata?.rewriteFrom ?? '',
    step.metadata?.rewriteTo ?? '',
    step.metadata?.rewriteScenario ?? '',
    step.metadata?.hitlStatus ?? '',
    step.metadata?.hitlToken ?? '',
    step.metadata?.recoveryStatus ?? '',
    step.metadata?.recoveryToken ?? '',
    step.metadata?.nodeAttempts?.map(a => `${a.attemptNo}:${a.status}:${a.summary ?? ''}`).join('|') ?? '',
    subStepsSignature(step.subSteps),
  ].join('\u0001')
}

function attemptsSignature(node?: DagNodeView): string {
  return node?.attempts?.map(a => `${a.attemptNo}:${a.status}:${a.summary ?? ''}`).join('|') ?? ''
}

function syncDrawerSelection(nodes: DagNodeView[]) {
  const id = planId.value
  if (!id || !isActivePlan(id) || !drawerState.node) return
  const nodeId = drawerState.node.id
  const fresh = nodes.find(n => n.id === nodeId)
  if (!fresh) return
  const step = stepForNode(nodeId)
  const cur = drawerState.node
  if (
    fresh.status !== cur.status
    || fresh.durationMs !== cur.durationMs
    || fresh.summary !== cur.summary
    || fresh.detail !== cur.detail
    || fresh.recoveryAwaiting !== cur.recoveryAwaiting
    || attemptsSignature(fresh) !== attemptsSignature(cur)
  ) {
    drawerState.node = fresh
  }
  if (stepContentSignature(step) !== stepContentSignature(drawerState.step)) {
    drawerState.step = step
  }
}

function nodeNeedsDrawerAttention(node: DagNodeView): boolean {
  if (node.status === 'paused') return false
  return node.status === 'awaiting_confirm' || (!!node.recoveryAwaiting && node.status === 'error')
}

function maybeAutoOpenDrawer(nodes: DagNodeView[]) {
  const id = planId.value
  if (!id || !props.live) return
  const target = nodes.find(nodeNeedsDrawerAttention)
  if (!target) return
  if (isActivePlan(id) && drawerState.node?.id === target.id) return
  // 用户已打开抽屉并手动点了其他节点时，不要抢回（三开时 sync 更频繁）
  if (isActivePlan(id) && drawerState.node && drawerState.node.id !== target.id) return
  openPlanNodeDrawer({ planId: id, userQuery: props.userQuery, node: target, step: stepForNode(target.id), graph: graphSource.value })
}

const planDetail = ref<ExecutionPlanDetail | null>(null)
/** 校验通过的 Plan 拓扑只加载一次，执行期状态由 SSE nodeSteps 驱动 */
const frozenGraph = ref<PlanGraph | null>(null)
const graphPlanId = ref<string | null>(null)
const skillCatalog = ref<SkillCatalogIndexEntry[]>([])
const loadingPlan = ref(false)

const planId = computed(() => {
  const fromStep = resolvePlanIdFromStep(props.planStep)
  if (fromStep) return fromStep
  if (props.executionPlanId) return props.executionPlanId
  return undefined
})

const graphSource = computed(() => frozenGraph.value ?? undefined)

const nodeSteps = computed(() => listPlanDagNodeSteps(props.allSteps))

const nodeTraces = computed(() => planDetail.value?.nodes ?? [])

const dagNodes = computed(() =>
  buildDagNodes(
    graphSource.value,
    nodeSteps.value,
    nodeTraces.value.length ? nodeTraces.value : undefined,
    skillCatalog.value,
    props.planStep,
    props.pendingHitlConfirmations,
  ),
)

const lifecycle = computed(() => stepLifecycle(props.planStep))
const isRunning = computed(() => lifecycle.value === 'running')
const label = computed(() => formatStepLabel(props.planStep))
const durationText = computed(() => {
  const ms = resolveStepDurationMs(props.planStep)
  return ms != null ? formatDuration(ms) : ''
})

const selectedId = computed(() =>
  isActivePlan(planId.value) ? drawerState.node?.id : undefined,
)

function stepForNode(nodeId: string): ProcessingStep | undefined {
  return resolveDagNodeStep(nodeId, props.allSteps, graphSource.value, props.planStep)
}

function onSelectNode(node: DagNodeView) {
  const id = planId.value
  if (!id) return
  openPlanNodeDrawer({
    planId: id,
    userQuery: props.userQuery,
    node,
    step: stepForNode(node.id),
    graph: graphSource.value,
  })
}

function onExpandDag() {
  const id = planId.value
  const graph = graphSource.value
  if (!id || !graph?.nodes?.length) return
  openExpand({
    planId: id,
    title: label.value,
    userQuery: props.userQuery,
    graph,
    nodes: dagNodes.value,
    selectedId: selectedId.value,
    live: props.live,
  }, onSelectNode)
}

function dagNodesSignature(nodes: DagNodeView[]): string {
  return nodes.map(n => [
    n.id,
    n.status,
    n.durationMs ?? '',
    n.attemptCount ?? '',
    n.summary ?? '',
    n.recoveryAwaiting ? '1' : '0',
    attemptsSignature(n),
  ].join(':')).join('|')
}

const dagNodesSig = computed(() => dagNodesSignature(dagNodes.value))

let lastExpandSyncSig = ''

function syncExpandLayer() {
  const id = planId.value
  const graph = graphSource.value
  if (!id || !graph || !isExpanded(id)) return
  const syncSig = [
    label.value,
    selectedId.value ?? '',
    dagNodesSig.value,
    props.live ? '1' : '0',
    props.userQuery ?? '',
  ].join('\u0001')
  if (syncSig !== lastExpandSyncSig) {
    lastExpandSyncSig = syncSig
    updateExpand({
      title: label.value,
      userQuery: props.userQuery,
      graph,
      nodes: dagNodes.value,
      selectedId: selectedId.value,
      live: props.live,
    })
  }
  bindSelect(id, onSelectNode)
}

async function loadPlan() {
  const id = planId.value
  if (!id) return
  const hasGraph = graphPlanId.value === id && !!frozenGraph.value
  // 流式执行期拓扑只拉一次；终态/刷新须拉 execution_trace 恢复节点着色
  if (props.live && hasGraph) return

  const firstLoad = !hasGraph
  if (firstLoad) loadingPlan.value = true
  try {
    const detail = await getExecutionPlan(id)
    planDetail.value = detail
    if (!hasGraph) {
      if (detail.validatedPlan?.nodes?.length) {
        frozenGraph.value = detail.validatedPlan
        graphPlanId.value = id
      } else if (detail.plan?.nodes?.length) {
        frozenGraph.value = detail.plan
        graphPlanId.value = id
      }
    }
  } catch {
    if (!planDetail.value) planDetail.value = null
  } finally {
    if (firstLoad) loadingPlan.value = false
  }
}

function resetGraphForPlan(id: string | undefined) {
  if (!id) {
    frozenGraph.value = null
    graphPlanId.value = null
    planDetail.value = null
    return
  }
  if (graphPlanId.value === id) return
  frozenGraph.value = null
  graphPlanId.value = null
  planDetail.value = null
}

onMounted(() => {
  void loadPlan()
  void listSkillCatalogIndex().then(list => { skillCatalog.value = list }).catch(() => {})
  const id = planId.value
  if (id) registerPlanDagSelectHandler(id, onSelectNode)
})

onUnmounted(() => {
  const id = planId.value
  if (id) unregisterPlanDagSelectHandler(id)
})
watch(planId, (id, prev) => {
  if (prev) unregisterPlanDagSelectHandler(prev)
  if (id) registerPlanDagSelectHandler(id, onSelectNode)
  if (id === prev) return
  lastExpandSyncSig = ''
  if (expandState.activePlanId && expandState.activePlanId === prev && id) {
    expandState.activePlanId = id
  } else if (expandState.activePlanId && expandState.activePlanId !== id) {
    closeExpand()
  }
  resetGraphForPlan(id)
  void loadPlan()
})
watch(
  () => props.live,
  (live, prevLive) => {
    if (prevLive && !live && planId.value) void loadPlan()
  },
)
watch(
  () => props.executionPlanId,
  (id, prev) => {
    if (id && id !== prev && planId.value) void loadPlan()
  },
)
watch(dagNodesSig, () => {
  const nodes = dagNodes.value
  syncDrawerSelection(nodes)
  syncExpandLayer()
  maybeAutoOpenDrawer(nodes)
})
watch(
  () => {
    const nid = drawerState.node?.id
    if (!nid || !isActivePlan(planId.value)) return ''
    return stepContentSignature(stepForNode(nid))
  },
  () => syncDrawerSelection(dagNodes.value),
)
watch(() => expandState.activePlanId, (activeId) => {
  if (!activeId || activeId !== planId.value) lastExpandSyncSig = ''
})
watch(selectedId, () => syncExpandLayer())
</script>

<template>
  <div class="plan-panel op-line">
    <div class="op-line-row">
      <span class="op-main">
        <span class="op-label" :class="{ 'op-shimmer': isRunning && live }">{{ label }}</span>
      </span>
      <span v-if="durationText" class="op-dur">{{ durationText }}</span>
    </div>
    <PlanExecutionCanvas
      v-if="graphSource && dagNodes.length && !isExpanded(planId)"
      :graph="graphSource"
      :dag-nodes="dagNodes"
      :selected-id="selectedId"
      :live="live"
      :show-expand="true"
      @select="onSelectNode"
      @expand="onExpandDag"
    />
    <!-- 放大时保留占位，避免布局跳动 -->
    <div v-if="dagNodes.length && isExpanded(planId)" class="plan-dag-collapsed-slot" aria-hidden="true" />
    <div v-else-if="loadingPlan && !frozenGraph" class="plan-dag-skeleton">加载执行图…</div>
  </div>
</template>

<style scoped>
.plan-panel {
  font-size: var(--sun-font-md);
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.op-line-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  column-gap: 4px;
  align-items: start;
  padding: 1px 0;
}

.op-main {
  display: flex;
  flex-wrap: nowrap;
  align-items: baseline;
  gap: 0 6px;
  min-width: 0;
}

.op-label {
  flex-shrink: 0;
  color: var(--sun-text-secondary);
  font-weight: 450;
}

.op-text {
  flex: 1 1 0;
  color: var(--sun-text-muted);
  opacity: 0.92;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}

.op-dur {
  flex-shrink: 0;
  padding-left: 10px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  opacity: 0.65;
  font-variant-numeric: tabular-nums;
}

.op-shimmer {
  color: var(--sun-text);
}

.plan-dag-skeleton {
  margin: 8px 0 4px 0;
  padding: 16px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  border: 1px dashed var(--sun-border);
  border-radius: 10px;
}

.plan-dag-collapsed-slot {
  margin: 8px 0 4px 0;
  min-height: 94px;
}
</style>
