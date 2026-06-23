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
import { getExecutionPlan, type ExecutionPlanDetail } from '../../api/executionPlans'
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

const planDetail = ref<ExecutionPlanDetail | null>(null)
const skillCatalog = ref<SkillCatalogIndexEntry[]>([])
const loadingPlan = ref(false)

const planId = computed(() =>
  resolvePlanIdFromStep(props.planStep) ?? props.executionPlanId,
)

const graphSource = computed(() => {
  const d = planDetail.value
  if (!d) return undefined
  return d.validatedPlan?.nodes?.length ? d.validatedPlan : d.plan
})

const nodeSteps = computed(() =>
  props.allSteps.filter(s => s.phase === 'node' && s.id.startsWith('node-')),
)

const dagNodes = computed(() =>
  buildDagNodes(
    graphSource.value,
    nodeSteps.value,
    planDetail.value?.nodes,
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
  loadingPlan.value = true
  try {
    planDetail.value = await getExecutionPlan(id)
  } catch {
    planDetail.value = null
  } finally {
    loadingPlan.value = false
  }
}

onMounted(() => {
  void loadPlan()
  void listSkillCatalogIndex().then(list => { skillCatalog.value = list }).catch(() => {})
})
watch(planId, () => { void loadPlan() })
watch(
  () => lifecycle.value,
  (lc, prev) => {
    if (lc === 'done' && prev === 'running') void loadPlan()
  },
)
watch(nodeSteps, () => {
  if (!drawerState.open) return
  void loadPlan()
}, { deep: true })
watch(dagNodes, (nodes) => {
  if (!drawerState.open || !drawerState.node) return
  const fresh = nodes.find(n => n.id === drawerState.node!.id)
  if (fresh) {
    openDrawer({ node: fresh, step: stepForNode(fresh.id) })
  }
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
    <div v-else-if="loadingPlan" class="plan-dag-skeleton">加载执行图…</div>
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
