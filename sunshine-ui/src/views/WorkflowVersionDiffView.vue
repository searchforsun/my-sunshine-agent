<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NEmpty, NSelect, NSpin, NTag, useMessage } from 'naive-ui'
import WorkflowDagEditor from '../components/workflows/WorkflowDagEditor.vue'
import {
  getWorkflowVersion,
  listWorkflowVersions,
  listWorkflows,
  exportWorkflowVersion,
  type WorkflowPlan,
  type WorkflowVersion,
} from '../api/workflows'
import { friendlyErrorMessage } from '../api/apiError'
import { copyText } from '../utils/stream-markdown/clipboard'
import { autoLayoutPlan } from '../utils/workflowDagLayout'
import {
  diffWorkflowPlanJson,
  hasWorkflowPlanDiff,
  summarizeWorkflowPlanDiff,
  toWorkflowSplitDiffRows,
  workflowDiffPrefix,
  type WorkflowJsonDiffLine,
} from '../utils/workflowPlanDiff'
import {
  versionOptionLabel,
  versionStatusLabel,
  versionStatusTagType,
  resolveVersionStatus,
} from '../utils/workflows/workflowsVersionUtils'

type DiffViewMode = 'inline' | 'split'
type DiffContentTab = 'summary' | 'canvas' | 'json'

const route = useRoute()
const router = useRouter()
const message = useMessage()

const loading = ref(true)
const diffLoading = ref(false)
const versions = ref<WorkflowVersion[]>([])
const activeVersion = ref<number | null>(null)
const fromPlan = ref<WorkflowPlan | null>(null)
const toPlan = ref<WorkflowPlan | null>(null)
const viewMode = ref<DiffViewMode>('inline')
const contentTab = ref<DiffContentTab>('summary')
const canvasReady = ref(false)

const workflowId = computed(() => String(route.params.workflowId ?? ''))
const fromVersion = computed({
  get: () => Number.parseInt(String(route.query.from ?? ''), 10),
  set: (v: number) => syncQuery({ from: String(v) }),
})
const toVersion = computed({
  get: () => Number.parseInt(String(route.query.to ?? ''), 10),
  set: (v: number) => syncQuery({ to: String(v) }),
})

const versionOptions = computed(() =>
  versions.value.map(v => ({
    label: versionOptionLabel(v),
    value: v.version,
  })),
)

const fromVersionEntry = computed(() =>
  versions.value.find(v => v.version === fromVersion.value) ?? null,
)
const toVersionEntry = computed(() =>
  versions.value.find(v => v.version === toVersion.value) ?? null,
)
const fromVersionStatus = computed(() =>
  resolveVersionStatus(fromVersionEntry.value, activeVersion.value),
)
const toVersionStatus = computed(() =>
  resolveVersionStatus(toVersionEntry.value, activeVersion.value),
)

const summary = computed(() => {
  if (!fromPlan.value || !toPlan.value) return null
  return summarizeWorkflowPlanDiff(fromPlan.value, toPlan.value)
})

const diffLines = computed((): WorkflowJsonDiffLine[] => {
  if (!fromPlan.value || !toPlan.value) return []
  return diffWorkflowPlanJson(fromPlan.value, toPlan.value)
})

const splitRows = computed(() => toWorkflowSplitDiffRows(diffLines.value))

const hasDiff = computed(() => summary.value != null && hasWorkflowPlanDiff(summary.value))

const fromPlanLayout = computed(() =>
  fromPlan.value ? autoLayoutPlan(fromPlan.value) : null,
)
const toPlanLayout = computed(() =>
  toPlan.value ? autoLayoutPlan(toPlan.value) : null,
)

const diffHighlightNodeIds = computed(() => {
  if (!summary.value) return new Set<string>()
  const ids = new Set<string>()
  for (const id of summary.value.addedNodes) ids.add(id)
  for (const id of summary.value.removedNodes) ids.add(id)
  for (const item of summary.value.changedNodes) ids.add(item.id)
  return ids
})

watch(contentTab, (tab) => {
  if (tab !== 'canvas') {
    canvasReady.value = false
    return
  }
  canvasReady.value = false
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      canvasReady.value = true
    })
  })
})

function versionLabel(versionNum: number): string {
  const entry = versions.value.find(v => v.version === versionNum)
  return entry ? versionOptionLabel(entry) : '—'
}

function syncQuery(patch: Record<string, string>) {
  void router.replace({
    name: 'workflow-diff',
    params: { workflowId: workflowId.value },
    query: { ...route.query, ...patch },
  })
}

function goBack() {
  if (window.history.length > 1) {
    router.back()
  } else {
    void router.push({ name: 'workflows', params: { workflowId: workflowId.value } })
  }
}

async function exportVersion(
  version: number,
  mode: 'download' | 'copy',
  role: 'baseline' | 'compare',
) {
  try {
    const body = await exportWorkflowVersion(workflowId.value, version)
    const text = JSON.stringify(body, null, 2)
    if (mode === 'copy') {
      const ok = await copyText(text)
      if (!ok) throw new Error('复制失败')
      message.success('对比 JSON 已复制')
      return
    }
    const blob = new Blob([text], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${workflowId.value}-${role === 'baseline' ? 'baseline' : 'compare'}-v${version}.json`
    a.click()
    URL.revokeObjectURL(url)
    message.success(role === 'baseline' ? '基准版本 JSON 已导出' : '对比版本 JSON 已导出')
  } catch (e) {
    message.error(friendlyErrorMessage(e, mode === 'copy' ? '复制失败' : '导出失败'))
  }
}

async function loadVersions() {
  const [versionList, workflowList] = await Promise.all([
    listWorkflowVersions(workflowId.value),
    listWorkflows(),
  ])
  versions.value = versionList
  activeVersion.value = workflowList.find(w => w.id === workflowId.value)?.activeVersion ?? null
}

async function loadPlans() {
  if (!Number.isFinite(fromVersion.value) || !Number.isFinite(toVersion.value)) {
    message.error('缺少对比版本参数')
    return
  }
  diffLoading.value = true
  try {
    const [fromData, toData] = await Promise.all([
      getWorkflowVersion(workflowId.value, fromVersion.value),
      getWorkflowVersion(workflowId.value, toVersion.value),
    ])
    fromPlan.value = fromData.plan
    toPlan.value = toData.plan
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载版本 Plan 失败'))
  } finally {
    diffLoading.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await loadVersions()
    let from = fromVersion.value
    let to = toVersion.value
    if ((!Number.isFinite(from) || !Number.isFinite(to)) && versions.value.length >= 2) {
      const sorted = [...versions.value].sort((a, b) => a.version - b.version)
      from = sorted[sorted.length - 2].version
      to = sorted[sorted.length - 1].version
      await router.replace({
        name: 'workflow-diff',
        params: { workflowId: workflowId.value },
        query: { from: String(from), to: String(to) },
      })
    }
    if (Number.isFinite(from) && Number.isFinite(to)) {
      diffLoading.value = true
      try {
        const [fromData, toData] = await Promise.all([
          getWorkflowVersion(workflowId.value, from),
          getWorkflowVersion(workflowId.value, to),
        ])
        fromPlan.value = fromData.plan
        toPlan.value = toData.plan
      } finally {
        diffLoading.value = false
      }
    }
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载版本对比失败'))
  } finally {
    loading.value = false
  }
})

watch([fromVersion, toVersion], () => {
  if (loading.value) return
  void loadPlans()
})
</script>

<template>
  <section class="wf-diff-page">
    <header class="wf-diff-head">
      <div class="wf-diff-title-block">
        <NButton size="small" quaternary class="wf-diff-back" @click="goBack">← 返回</NButton>
        <div>
          <h2 class="wf-diff-title">{{ workflowId }} · 版本对比</h2>
          <p class="wf-diff-sub">{{ versionLabel(fromVersion) }} → {{ versionLabel(toVersion) }}</p>
        </div>
      </div>
      <div class="wf-diff-controls">
        <div class="wf-diff-version-pick">
          <NTag
            size="small"
            :bordered="false"
            round
            :type="versionStatusTagType(fromVersionStatus)"
          >
            {{ versionStatusLabel(fromVersionStatus) }}
          </NTag>
          <NSelect
            :value="fromVersion"
            :options="versionOptions"
            size="small"
            class="version-select wf-diff-version-select"
            placeholder="基准版本"
            :menu-props="{ class: 'version-select-menu' }"
            @update:value="v => { fromVersion = v as number }"
          />
        </div>
        <span class="wf-diff-arrow">→</span>
        <div class="wf-diff-version-pick">
          <NTag
            size="small"
            :bordered="false"
            round
            :type="versionStatusTagType(toVersionStatus)"
          >
            {{ versionStatusLabel(toVersionStatus) }}
          </NTag>
          <NSelect
            :value="toVersion"
            :options="versionOptions"
            size="small"
            class="version-select wf-diff-version-select"
            placeholder="对比版本"
            :menu-props="{ class: 'version-select-menu' }"
            @update:value="v => { toVersion = v as number }"
          />
        </div>
        <NButton size="small" round secondary @click="void exportVersion(fromVersion, 'download', 'baseline')">
          导出基准
        </NButton>
        <NButton size="small" round secondary @click="void exportVersion(toVersion, 'download', 'compare')">
          导出对比
        </NButton>
        <NButton size="small" round secondary @click="void exportVersion(toVersion, 'copy', 'compare')">
          复制对比 JSON
        </NButton>
      </div>
    </header>

    <div class="wf-diff-tabs">
      <button
        type="button"
        class="tab-btn"
        :class="{ active: contentTab === 'summary' }"
        @click="contentTab = 'summary'"
      >
        摘要
      </button>
      <button
        type="button"
        class="tab-btn"
        :class="{ active: contentTab === 'canvas' }"
        @click="contentTab = 'canvas'"
      >
        画布
      </button>
      <button
        type="button"
        class="tab-btn"
        :class="{ active: contentTab === 'json' }"
        @click="contentTab = 'json'"
      >
        JSON
      </button>
    </div>

    <NSpin :show="loading || diffLoading">
      <div v-if="summary" class="wf-diff-body">
        <section v-if="contentTab === 'summary'" class="wf-diff-summary">
          <h3 class="block-title">变更摘要</h3>
          <p v-if="!hasDiff" class="wf-diff-empty-line">两版本 Plan 无差异（不含 layout）</p>
          <ul v-else class="wf-diff-summary-list">
            <li v-if="summary.reasonChanged">规划说明 reason 已变更</li>
            <li v-if="summary.addedNodes.length">新增节点：{{ summary.addedNodes.join('、') }}</li>
            <li v-if="summary.removedNodes.length">删除节点：{{ summary.removedNodes.join('、') }}</li>
            <li v-for="item in summary.changedNodes" :key="item.id">
              修改节点 {{ item.label }}（{{ item.id }}）：{{ item.fields.join('、') }}
            </li>
            <li v-if="summary.addedEdges.length">新增连线：{{ summary.addedEdges.join('；') }}</li>
            <li v-if="summary.removedEdges.length">删除连线：{{ summary.removedEdges.join('；') }}</li>
          </ul>
        </section>

        <section v-else-if="contentTab === 'canvas'" class="wf-diff-canvas-grid">
          <div class="wf-diff-canvas-pane">
            <div class="wf-diff-canvas-head">{{ versionLabel(fromVersion) }} · 基准</div>
            <div class="wf-diff-canvas-wrap">
              <WorkflowDagEditor
                v-if="fromPlanLayout && canvasReady"
                :key="`from-${fromVersion}-${canvasReady}`"
                :plan="fromPlanLayout"
                read-only
                :issue-node-ids="diffHighlightNodeIds"
              />
            </div>
          </div>
          <div class="wf-diff-canvas-pane">
            <div class="wf-diff-canvas-head">{{ versionLabel(toVersion) }} · 对比</div>
            <div class="wf-diff-canvas-wrap">
              <WorkflowDagEditor
                v-if="toPlanLayout && canvasReady"
                :key="`to-${toVersion}-${canvasReady}`"
                :plan="toPlanLayout"
                read-only
                :issue-node-ids="diffHighlightNodeIds"
              />
            </div>
          </div>
        </section>

        <section v-else class="wf-diff-json">
          <div class="wf-diff-json-head">
            <h3 class="block-title">Plan JSON</h3>
            <div class="wf-diff-mode">
              <button
                type="button"
                class="mode-btn"
                :class="{ active: viewMode === 'inline' }"
                @click="viewMode = 'inline'"
              >
                行内
              </button>
              <button
                type="button"
                class="mode-btn"
                :class="{ active: viewMode === 'split' }"
                @click="viewMode = 'split'"
              >
                分列
              </button>
            </div>
          </div>
          <div v-if="viewMode === 'inline'" class="diff-inline">
            <div
              v-for="(line, idx) in diffLines"
              :key="idx"
              class="diff-line"
              :class="`is-${line.type}`"
            >
              <span class="diff-gutter">{{ line.oldLineNo ?? '' }}</span>
              <span class="diff-gutter right">{{ line.newLineNo ?? '' }}</span>
              <code>{{ workflowDiffPrefix(line.type) }}{{ line.text }}</code>
            </div>
          </div>
          <div v-else class="diff-split">
            <div class="diff-split-col">
              <div class="diff-col-head">{{ versionLabel(fromVersion) }}</div>
              <div
                v-for="(row, idx) in splitRows"
                :key="`l-${idx}`"
                class="diff-line"
                :class="row.left.type === 'empty' ? 'is-empty' : `is-${row.left.type}`"
              >
                <span class="diff-gutter">{{ row.left.lineNo ?? '' }}</span>
                <code>{{ row.left.text }}</code>
              </div>
            </div>
            <div class="diff-split-col">
              <div class="diff-col-head">{{ versionLabel(toVersion) }}</div>
              <div
                v-for="(row, idx) in splitRows"
                :key="`r-${idx}`"
                class="diff-line"
                :class="row.right.type === 'empty' ? 'is-empty' : `is-${row.right.type}`"
              >
                <span class="diff-gutter">{{ row.right.lineNo ?? '' }}</span>
                <code>{{ row.right.text }}</code>
              </div>
            </div>
          </div>
        </section>
      </div>
      <NEmpty v-else description="无法加载对比数据" />
    </NSpin>
  </section>
</template>

<style scoped>
.wf-diff-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  height: 100%;
  padding: 16px;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: 0;
}

.wf-diff-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.wf-diff-title-block {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.wf-diff-back {
  flex-shrink: 0;
}

.wf-diff-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--sun-text);
}

.wf-diff-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.wf-diff-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.wf-diff-version-pick {
  display: flex;
  align-items: center;
  gap: 8px;
}

.wf-diff-version-select {
  width: 196px;
  flex-shrink: 0;
}

.wf-diff-version-select :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-arrow-color: var(--sun-text-secondary) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-active: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.wf-diff-version-select :deep(.n-base-selection-label) {
  overflow: visible;
  text-overflow: clip;
  white-space: nowrap;
}

.wf-diff-arrow {
  color: var(--sun-text-muted);
  font-size: 12px;
}

.wf-diff-mode {
  display: flex;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.mode-btn {
  border: none;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  font-size: 12px;
  padding: 6px 10px;
  cursor: pointer;
}

.mode-btn.active {
  color: var(--sun-text);
  box-shadow: inset 0 0 0 1px var(--sun-border-light);
}

.wf-diff-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.wf-diff-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tab-btn {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  font-size: 12px;
  padding: 6px 12px;
  cursor: pointer;
}

.tab-btn.active {
  color: var(--sun-text);
  box-shadow: inset 0 0 0 1px var(--sun-border-light);
}

.wf-diff-canvas-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  min-height: 420px;
}

.wf-diff-canvas-pane {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

.wf-diff-canvas-head {
  flex-shrink: 0;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--sun-text-muted);
  border-bottom: 1px solid var(--sun-border);
}

.wf-diff-canvas-wrap {
  flex: 1;
  min-height: 380px;
  display: flex;
  flex-direction: column;
}

.wf-diff-canvas-wrap :deep(.wf-dag-editor) {
  flex: 1;
  min-height: 0;
  height: 100%;
}

.wf-diff-canvas-wrap :deep(.wf-dag-canvas) {
  flex: 1;
  min-height: 360px;
  border: none;
  border-radius: 0;
}

.wf-diff-json-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.wf-diff-json-head .block-title {
  margin-bottom: 0;
}

@media (max-width: 900px) {
  .wf-diff-canvas-grid {
    grid-template-columns: 1fr;
  }
}

.wf-diff-summary,
.wf-diff-json {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  background: var(--sun-black);
}

.block-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text-secondary);
}

.wf-diff-summary-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--sun-text);
  line-height: 1.55;
}

.wf-diff-empty-line {
  margin: 0;
  font-size: 13px;
  color: var(--sun-text-muted);
}

.wf-diff-json {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.diff-inline,
.diff-split {
  flex: 1;
  min-height: 280px;
  max-height: calc(100vh - 280px);
  overflow: auto;
  font-family: var(--sun-font-mono);
  font-size: 12px;
  line-height: 1.45;
}

.diff-split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  background: var(--sun-border);
}

.diff-split-col {
  background: var(--sun-black);
  min-width: 0;
}

.diff-col-head {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 6px 10px;
  font-size: 11px;
  color: var(--sun-text-muted);
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.diff-line {
  display: flex;
  gap: 8px;
  padding: 0 8px;
  white-space: pre;
}

.diff-line code {
  flex: 1;
  min-width: 0;
  color: var(--sun-text-secondary);
}

.diff-gutter {
  width: 36px;
  flex-shrink: 0;
  text-align: right;
  color: var(--sun-text-muted);
  user-select: none;
}

.diff-gutter.right {
  width: 36px;
}

.diff-line.is-added {
  background: color-mix(in srgb, var(--sun-green, #3fb950) 12%, transparent);
}

.diff-line.is-removed {
  background: color-mix(in srgb, var(--sun-red, #f85149) 12%, transparent);
}

.diff-line.is-empty code {
  opacity: 0;
}
</style>

<style>
.version-select-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
}
</style>
