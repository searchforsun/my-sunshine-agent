<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
import { buildDagNodes, type DagNodeView } from '../../utils/planGraph'
import { relocateAgentNodeHitl } from '../../api/hitlSteps'
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'
import { usePlanDagExpand } from '../../composables/usePlanDagExpand'
import PlanDagGraph from './PlanDagGraph.vue'

const props = defineProps<{
  planStep: ProcessingStep
  allSteps: ProcessingStep[]
  live?: boolean
  executionPlanId?: string
  userQuery?: string
}>()

const { open: openDrawer, state: drawerState, isActivePlan } = usePlanNodeDrawer()
const { open: openExpand, close: closeExpand, isExpanded, update: updateExpand, state: expandState } = usePlanDagExpand()

function subStepsSignature(steps?: ProcessingStep[]): string {
  if (!steps?.length) return ''
  return steps.map(s => [
    s.id,
    s.lifecycle ?? s.status ?? '',
    s.summary?.after ?? '',
    s.summary?.active ?? '',
    s.reasoning?.length ?? 0,
    s.result?.length ?? 0,
    s.detail?.length ?? 0,
  ].join(':')).join('\u0002')
}

function stepContentSignature(step?: ProcessingStep): string {
  if (!step) return ''
  return [
    step.lifecycle ?? step.status ?? '',
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
  return node.status === 'awaiting_confirm' || (!!node.recoveryAwaiting && node.status === 'error')
}

function maybeAutoOpenDrawer(nodes: DagNodeView[]) {
  const id = planId.value
  if (!id || !props.live) return
  const target = nodes.find(nodeNeedsDrawerAttention)
  if (!target) return
  if (isActivePlan(id) && drawerState.node?.id === target.id) return
  openDrawer({ planId: id, userQuery: props.userQuery, node: target, step: stepForNode(target.id) })
}

const planDetail = ref<ExecutionPlanDetail | null>(null)
/** 校验通过的 Plan 拓扑只加载一次，执行期状态由 SSE nodeSteps 驱动 */
const frozenGraph = ref<PlanGraph | null>(null)
const graphPlanId = ref<string | null>(null)
const skillCatalog = ref<SkillCatalogIndexEntry[]>([])
const loadingPlan = ref(false)

const planId = computed(() =>
  resolvePlanIdFromStep(props.planStep) ?? props.executionPlanId,
)

const graphSource = computed(() => frozenGraph.value ?? undefined)

const nodeSteps = computed(() =>
  props.allSteps.filter(s => s.phase === 'node' && s.id.startsWith('node-')),
)

const dagNodes = computed(() =>
  buildDagNodes(
    graphSource.value,
    nodeSteps.value,
    props.live ? undefined : planDetail.value?.nodes,
    skillCatalog.value,
    props.planStep,
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
  if (nodeId === 'start') {
    return props.planStep
  }
  const step = nodeSteps.value.find(s => s.id === `node-${nodeId}`)
  return step?.id.startsWith('node-') ? relocateAgentNodeHitl(step) : step
}

function onSelectNode(node: DagNodeView) {
  const id = planId.value
  if (!id) return
  openDrawer({ planId: id, userQuery: props.userQuery, node, step: stepForNode(node.id) })
}

function onExpandDag() {
  const id = planId.value
  if (!id) return
  openExpand({
    planId: id,
    title: label.value,
    userQuery: props.userQuery,
    nodes: dagNodes.value,
    selectedId: selectedId.value,
    live: props.live,
  }, onSelectNode)
}

function syncExpandLayer() {
  const id = planId.value
  if (!id || !isExpanded(id)) return
  updateExpand({
    title: label.value,
    userQuery: props.userQuery,
    nodes: dagNodes.value,
    selectedId: selectedId.value,
    live: props.live,
  })
}

async function loadPlan() {
  const id = planId.value
  if (!id) return
  const hasGraph = graphPlanId.value === id && !!frozenGraph.value
  // 流式执行期图结构只拉一次，避免 answer 阶段重复请求导致拓扑闪动
  if (props.live && hasGraph) return

  const firstLoad = !hasGraph
  if (firstLoad) loadingPlan.value = true
  try {
    const detail = await getExecutionPlan(id)
    planDetail.value = detail
    if (detail.validatedPlan?.nodes?.length) {
      frozenGraph.value = detail.validatedPlan
      graphPlanId.value = id
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
})
watch(planId, (id, prev) => {
  if (id === prev) return
  if (expandState.activePlanId && expandState.activePlanId !== id) closeExpand()
  resetGraphForPlan(id)
  void loadPlan()
})
watch(dagNodes, (nodes) => {
  syncDrawerSelection(nodes)
  syncExpandLayer()
  maybeAutoOpenDrawer(nodes)
}, { deep: true })
watch(selectedId, () => syncExpandLayer())
</script>

<template>
  <div class="plan-panel op-line">
    <div class="op-line-row">
      <span class="op-gutter" aria-hidden="true" />
      <span class="op-main">
        <span class="op-label" :class="{ 'op-shimmer': isRunning && live }">{{ label }}</span>
      </span>
      <span v-if="durationText" class="op-dur">{{ durationText }}</span>
    </div>
    <PlanDagGraph
      v-if="dagNodes.length && !isExpanded(planId)"
      :nodes="dagNodes"
      :selected-id="selectedId"
      :live="live"
      show-expand
      @select="onSelectNode"
      @expand="onExpandDag"
    />
    <!-- 放大时保留占位，避免布局跳动 -->
    <div v-else-if="dagNodes.length && isExpanded(planId)" class="plan-dag-collapsed-slot" aria-hidden="true" />
    <div v-else-if="loadingPlan && !frozenGraph" class="plan-dag-skeleton">加载执行图…</div>
  </div>
</template>

<style scoped>
.plan-panel {
  --op-gutter: 12px;
  font-size: var(--sun-font-md);
  line-height: 1.5;
  color: var(--sun-text-muted);
}

.op-line-row {
  display: grid;
  grid-template-columns: var(--op-gutter) minmax(0, 1fr) auto;
  column-gap: 4px;
  align-items: start;
  padding: 1px 0;
}

.op-gutter {
  width: var(--op-gutter);
  flex-shrink: 0;
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
  margin: 8px 0 4px calc(var(--op-gutter) + 4px);
  padding: 16px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
  border: 1px dashed var(--sun-border);
  border-radius: 10px;
}

.plan-dag-collapsed-slot {
  margin: 8px 0 4px calc(var(--op-gutter) + 4px);
  min-height: 94px;
}
</style>
