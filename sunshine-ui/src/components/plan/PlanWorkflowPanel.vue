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
import { usePlanNodeDrawer } from '../../composables/usePlanNodeDrawer'
import PlanDagGraph from './PlanDagGraph.vue'

const props = defineProps<{
  planStep: ProcessingStep
  allSteps: ProcessingStep[]
  live?: boolean
  executionPlanId?: string
}>()

const { open: openDrawer, state: drawerState } = usePlanNodeDrawer()

function stepContentSignature(step?: ProcessingStep): string {
  if (!step) return ''
  return [
    step.lifecycle ?? step.status ?? '',
    step.result ?? '',
    step.detail ?? '',
    step.reasoning ?? '',
  ].join('\u0001')
}

function syncDrawerSelection(nodes: DagNodeView[]) {
  if (!drawerState.open || !drawerState.node) return
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
  ) {
    drawerState.node = fresh
  }
  if (stepContentSignature(step) !== stepContentSignature(drawerState.step)) {
    drawerState.step = step
  }
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
  ),
)

const lifecycle = computed(() => stepLifecycle(props.planStep))
const isRunning = computed(() => lifecycle.value === 'running')
const label = computed(() => formatStepLabel(props.planStep))
const durationText = computed(() => {
  const ms = resolveStepDurationMs(props.planStep)
  return ms != null ? formatDuration(ms) : ''
})

const selectedId = computed(() => drawerState.node?.id)

function stepForNode(nodeId: string): ProcessingStep | undefined {
  return nodeSteps.value.find(s => s.id === `node-${nodeId}`)
}

function onSelectNode(node: DagNodeView) {
  openDrawer({ node, step: stepForNode(node.id) })
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
  resetGraphForPlan(id)
  void loadPlan()
})
watch(dagNodes, (nodes) => {
  syncDrawerSelection(nodes)
}, { deep: true })
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
      v-if="dagNodes.length"
      :nodes="dagNodes"
      :selected-id="selectedId"
      :live="live"
      @select="onSelectNode"
    />
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
</style>
