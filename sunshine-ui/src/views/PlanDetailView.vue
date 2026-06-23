<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NTag, NSpin, NResult, NText } from 'naive-ui'
import {
  getExecutionPlan,
  formatPlanStatus,
  formatPlanNodeType,
  formatTraceStatus,
  type ExecutionPlanDetail,
  type PlanGraphNode,
  type PlanNodeTrace,
} from '../api/executionPlans'
import { formatDuration } from '../api/processingSteps'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const error = ref('')
const detail = ref<ExecutionPlanDetail | null>(null)

const planId = computed(() => String(route.params.planId ?? ''))

const planNodes = computed<PlanGraphNode[]>(() => {
  const graph = detail.value?.validatedPlan?.nodes?.length
    ? detail.value.validatedPlan
    : detail.value?.plan
  return graph?.nodes?.filter(n => n.type !== 'start' && n.type !== 'answer') ?? []
})

function statusTagType(status: string): 'default' | 'info' | 'success' | 'warning' | 'error' {
  if (status === 'completed') return 'success'
  if (status === 'running' || status === 'validated') return 'info'
  if (status === 'failed' || status === 'rejected') return 'error'
  if (status === 'draft') return 'warning'
  return 'default'
}

function traceTagType(status: string): 'default' | 'success' | 'error' | 'info' {
  if (status === 'completed') return 'success'
  if (status === 'failed') return 'error'
  if (status === 'running') return 'info'
  return 'default'
}

function formatTime(iso?: string): string {
  if (!iso) return '—'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return iso
  return new Date(t).toLocaleString()
}

function nodeLabel(node: PlanGraphNode): string {
  if (node.displayName?.trim()) return node.displayName.trim()
  return formatPlanNodeType(node.type)
}

function traceDuration(trace?: PlanNodeTrace): string {
  if (!trace?.startedAt || !trace?.endedAt) return ''
  return formatDuration(trace.endedAt - trace.startedAt)
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push('/chat')
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await getExecutionPlan(planId.value)
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '加载失败'
    detail.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="plan-root">
    <div class="plan-content">
      <header class="page-header">
        <div class="header-row">
          <NButton size="small" quaternary @click="goBack">← 返回</NButton>
          <div>
            <h2>执行计划</h2>
            <p v-if="detail">会话 {{ detail.conversationId }} · 消息 {{ detail.messageId }}</p>
            <p v-else>Plan {{ planId }}</p>
          </div>
        </div>
      </header>

      <NSpin v-if="loading" class="plan-spin" />

      <NResult
        v-else-if="error"
        status="error"
        title="无法加载执行计划"
        :description="error"
      >
        <template #footer>
          <NButton @click="load">重试</NButton>
        </template>
      </NResult>

      <template v-else-if="detail">
        <section class="meta-card">
          <div class="meta-row">
            <NTag :type="statusTagType(detail.status)" :bordered="false" size="small">
              {{ formatPlanStatus(detail.status) }}
            </NTag>
            <NText depth="3" class="meta-id">{{ detail.id }}</NText>
          </div>
          <p v-if="detail.plannerReason" class="meta-reason">{{ detail.plannerReason }}</p>
          <p v-if="detail.rejectReason" class="meta-error">{{ detail.rejectReason }}</p>
          <div class="meta-times">
            <span>创建 {{ formatTime(detail.createdAt) }}</span>
            <span v-if="detail.startedAt">开始 {{ formatTime(detail.startedAt) }}</span>
            <span v-if="detail.completedAt">结束 {{ formatTime(detail.completedAt) }}</span>
          </div>
        </section>

        <section class="section-card">
          <h3>规划节点</h3>
          <div v-if="planNodes.length === 0" class="empty-hint">暂无业务节点</div>
          <div v-else class="node-list">
            <div v-for="(node, idx) in planNodes" :key="node.id" class="node-item">
              <div class="node-head">
                <span class="node-index">{{ idx + 1 }}</span>
                <span class="node-title">{{ nodeLabel(node) }}</span>
                <NTag size="tiny" :bordered="false">{{ node.type }}</NTag>
              </div>
              <div v-if="node.params && Object.keys(node.params).length" class="node-params">
                <span v-for="(val, key) in node.params" :key="key" class="param-chip">
                  {{ key }}={{ val }}
                </span>
              </div>
            </div>
          </div>
        </section>

        <section class="section-card">
          <h3>执行回放</h3>
          <div v-if="!detail.nodes.length" class="empty-hint">尚无节点执行记录</div>
          <div v-else class="trace-list">
            <div v-for="trace in detail.nodes" :key="`${trace.nodeId}-${trace.startedAt}`" class="trace-item">
              <div class="trace-head">
                <span class="trace-node">{{ trace.nodeId }}</span>
                <NTag size="tiny" :type="traceTagType(trace.status)" :bordered="false">
                  {{ formatTraceStatus(trace.status) }}
                </NTag>
                <span v-if="traceDuration(trace)" class="trace-dur">{{ traceDuration(trace) }}</span>
              </div>
              <div class="trace-type">{{ formatPlanNodeType(trace.type) }}</div>
              <div v-if="trace.summary" class="trace-summary">{{ trace.summary }}</div>
            </div>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.plan-root {
  height: 100vh;
  overflow-y: auto;
}

.plan-content {
  max-width: 780px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header h2 {
  font-size: 20px;
  font-weight: 700;
  margin: 0;
  color: var(--sun-text);
}

.page-header p {
  font-size: 13px;
  color: var(--sun-text-muted);
  margin: 4px 0 0;
}

.header-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.plan-spin {
  padding: 48px 0;
}

.meta-card,
.section-card {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  background: var(--sun-surface);
  padding: 16px 18px;
}

.meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.meta-id {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 12px;
}

.meta-reason {
  margin: 10px 0 0;
  font-size: 14px;
  color: var(--sun-text-secondary);
  line-height: 1.5;
}

.meta-error {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--sun-red);
}

.meta-times {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.section-card h3 {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--sun-text);
}

.empty-hint {
  font-size: 13px;
  color: var(--sun-text-muted);
}

.node-list,
.trace-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.node-item,
.trace-item {
  border: 1px solid color-mix(in srgb, var(--sun-border) 80%, transparent);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  background: var(--sun-deep);
}

.node-head,
.trace-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.node-index {
  width: 20px;
  height: 20px;
  border-radius: 999px;
  background: var(--sun-accent-muted);
  color: var(--sun-text-secondary);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.node-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--sun-text);
}

.node-params {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.param-chip {
  font-size: 11px;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  color: var(--sun-text-muted);
  background: var(--sun-accent-subtle);
  padding: 2px 6px;
  border-radius: 4px;
}

.trace-node {
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--sun-text);
}

.trace-dur {
  margin-left: auto;
  font-size: 12px;
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
}

.trace-type {
  margin-top: 4px;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.trace-summary {
  margin-top: 6px;
  font-size: 13px;
  color: var(--sun-text-secondary);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
