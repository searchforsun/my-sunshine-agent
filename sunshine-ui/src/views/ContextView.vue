<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  useMessage,
} from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import {
  getContextL1,
  getContextL3Status,
  listContextL2,
  reingestContextL3,
  runContextL3Gc,
  updateContextL2,
  voidContextL2,
  type L1Snapshot,
  type L2StateEntry,
  type L3Status,
} from '../api/contextAdmin'
import { useAuthStore } from '../stores/authStore'

const message = useMessage()
const auth = useAuthStore()

const filterUserId = ref(auth.user?.userId || '')
const filterTenantId = ref(auth.user?.tenantId || 'default')
const filterConvId = ref('')

const loading = ref(false)
const saving = ref(false)
const voiding = ref(false)
const loadingL1 = ref(false)
const loadingL3 = ref(false)
const runningGc = ref(false)
const reingesting = ref(false)

const entries = ref<L2StateEntry[]>([])
const selectedId = ref<string | null>(null)
const l1Snapshot = ref<L1Snapshot | null>(null)
const l3Status = ref<L3Status | null>(null)

const editForm = ref({
  stateValue: '',
  confidence: 0.75,
  status: 'active',
})

const statusOptions = [
  { label: 'active', value: 'active' },
  { label: 'superseded', value: 'superseded' },
  { label: 'void', value: 'void' },
]

const selected = computed(() =>
  entries.value.find(e => e.id === selectedId.value) ?? null,
)

const isFormDirty = computed(() => {
  const e = selected.value
  if (!e) return false
  return editForm.value.stateValue !== e.stateValue
    || editForm.value.confidence !== e.confidence
    || editForm.value.status !== e.status
})

function syncEditForm(entry: L2StateEntry | null) {
  if (!entry) return
  editForm.value = {
    stateValue: entry.stateValue ?? '',
    confidence: entry.confidence ?? 0,
    status: entry.status ?? 'active',
  }
}

function selectEntry(id: string) {
  selectedId.value = id
  syncEditForm(entries.value.find(e => e.id === id) ?? null)
}

async function loadL2() {
  const userId = filterUserId.value.trim()
  if (!userId) {
    message.warning('请填写 userId')
    return
  }
  loading.value = true
  try {
    entries.value = await listContextL2(userId, filterTenantId.value.trim() || 'default')
    if (selectedId.value && !entries.value.some(e => e.id === selectedId.value)) {
      selectedId.value = null
    }
    if (!selectedId.value && entries.value.length > 0) {
      selectEntry(entries.value[0].id)
    } else if (selectedId.value) {
      syncEditForm(selected.value)
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载 L2 失败')
  } finally {
    loading.value = false
  }
}

async function loadL1() {
  const convId = filterConvId.value.trim()
  if (!convId) {
    message.warning('请填写 convId')
    return
  }
  loadingL1.value = true
  try {
    l1Snapshot.value = await getContextL1(convId)
  } catch (e) {
    l1Snapshot.value = null
    message.error(e instanceof Error ? e.message : '加载 L1 失败')
  } finally {
    loadingL1.value = false
  }
}

async function loadL3() {
  const userId = filterUserId.value.trim()
  if (!userId) {
    message.warning('请填写 userId')
    return
  }
  loadingL3.value = true
  try {
    l3Status.value = await getContextL3Status(userId, filterTenantId.value.trim() || 'default')
  } catch (e) {
    l3Status.value = null
    message.error(e instanceof Error ? e.message : '加载 L3 状态失败')
  } finally {
    loadingL3.value = false
  }
}

async function handleSave() {
  if (!selected.value || !isFormDirty.value) return
  saving.value = true
  try {
    const updated = await updateContextL2(selected.value.id, {
      stateValue: editForm.value.stateValue,
      confidence: editForm.value.confidence,
      status: editForm.value.status,
    })
    const idx = entries.value.findIndex(e => e.id === updated.id)
    if (idx >= 0) entries.value[idx] = updated
    syncEditForm(updated)
    message.success('已保存')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleVoid() {
  if (!selected.value) return
  voiding.value = true
  try {
    const updated = await voidContextL2(selected.value.id)
    const idx = entries.value.findIndex(e => e.id === updated.id)
    if (idx >= 0) entries.value[idx] = updated
    syncEditForm(updated)
    message.success('已作废')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '作废失败')
  } finally {
    voiding.value = false
  }
}

async function handleGc() {
  runningGc.value = true
  try {
    const r = await runContextL3Gc()
    message.success(r.message || 'GC 完成')
    await loadL3()
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'GC 失败')
  } finally {
    runningGc.value = false
  }
}

async function handleReingest() {
  const convId = filterConvId.value.trim()
  if (!convId) {
    message.warning('请填写 convId')
    return
  }
  reingesting.value = true
  try {
    const r = await reingestContextL3(convId)
    message.success(`已提交 reingest：${r.ingested} 条`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'reingest 失败')
  } finally {
    reingesting.value = false
  }
}

function statusType(status: string): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'active') return 'success'
  if (status === 'superseded') return 'warning'
  if (status === 'void') return 'error'
  return 'default'
}

onMounted(() => {
  if (filterUserId.value.trim()) {
    void loadL2()
    void loadL3()
  }
})
</script>

<template>
  <div class="context-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>上下文</h2>
      </div>
      <NSpace :size="8">
        <NButton size="small" quaternary :loading="loading" @click="loadL2">
          <template #icon><NIcon :component="RefreshOutline" :size="16" /></template>
          刷新 L2
        </NButton>
      </NSpace>
    </header>

    <section class="filter-bar">
      <NForm class="filter-form" label-placement="left" :show-feedback="false" inline>
        <NFormItem label="userId">
          <NInput v-model:value="filterUserId" class="sun-field filter-input" placeholder="用户 ID" />
        </NFormItem>
        <NFormItem label="tenantId">
          <NInput v-model:value="filterTenantId" class="sun-field filter-input-sm" placeholder="default" />
        </NFormItem>
        <NFormItem label="convId">
          <NInput v-model:value="filterConvId" class="sun-field filter-input" placeholder="会话 ID（L1/reingest）" />
        </NFormItem>
        <NFormItem>
          <NSpace :size="8">
            <NButton size="small" type="primary" class="action-btn" :loading="loading" @click="loadL2">
              查 L2
            </NButton>
            <NButton size="small" secondary :loading="loadingL1" @click="loadL1">
              查 L1
            </NButton>
            <NButton size="small" secondary :loading="loadingL3" @click="loadL3">
              L3 状态
            </NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </section>

    <div class="context-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">L2 状态</span>
          <span class="panel-count">{{ entries.length }}</span>
        </div>
        <NSpin :show="loading" class="list-spin">
          <div v-if="entries.length" class="entry-list">
            <button
              v-for="item in entries"
              :key="item.id"
              type="button"
              class="entry-card"
              :class="{ active: item.id === selectedId }"
              @click="selectEntry(item.id)"
            >
              <div class="entry-top">
                <span class="entry-key">{{ item.kind }}/{{ item.stateKey }}</span>
                <NTag size="tiny" :type="statusType(item.status)" :bordered="false">
                  {{ item.status }}
                </NTag>
              </div>
              <p class="entry-value">{{ item.stateValue }}</p>
              <p class="entry-meta">置信 {{ item.confidence.toFixed(2) }}</p>
            </button>
          </div>
          <NEmpty v-else description="输入 userId 后查询 L2" class="list-empty" />
        </NSpin>
      </aside>

      <main v-if="selected" class="detail-panel">
        <div class="detail-head">
          <div>
            <h3 class="detail-title">{{ selected.kind }}/{{ selected.stateKey }}</h3>
            <p class="detail-sub">{{ selected.id }}</p>
          </div>
          <NSpace :size="8">
            <NButton
              size="small"
              secondary
              :loading="voiding"
              :disabled="selected.status === 'void'"
              @click="handleVoid"
            >
              作废
            </NButton>
            <NButton
              size="small"
              type="primary"
              class="action-btn"
              :loading="saving"
              :disabled="!isFormDirty"
              @click="handleSave"
            >
              保存
            </NButton>
          </NSpace>
        </div>
        <div class="detail-scroll">
          <NForm class="detail-form" label-placement="top" :show-feedback="false">
            <NFormItem label="值">
              <NInput
                v-model:value="editForm.stateValue"
                class="sun-field sun-field-grow"
                type="textarea"
                :autosize="{ minRows: 3, maxRows: 12 }"
              />
            </NFormItem>
            <div class="form-grid">
              <NFormItem label="置信度">
                <NInputNumber
                  v-model:value="editForm.confidence"
                  class="sun-field"
                  :min="0"
                  :max="1"
                  :step="0.05"
                />
              </NFormItem>
              <NFormItem label="状态">
                <NSelect
                  v-model:value="editForm.status"
                  class="sun-field"
                  :options="statusOptions"
                />
              </NFormItem>
            </div>
            <p class="meta-line">sourceMsgId: {{ selected.sourceMsgId || '—' }}</p>
            <p class="meta-line">expiresAt: {{ selected.expiresAt || '—' }}</p>
            <p class="meta-line">updatedAt: {{ selected.updatedAt || '—' }}</p>
          </NForm>
        </div>
      </main>
      <main v-else class="detail-panel detail-empty">
        <NEmpty description="选择左侧 L2 条目进行编辑" />
      </main>
    </div>

    <div class="aux-layout">
      <section class="aux-panel">
        <div class="panel-head">
          <span class="panel-title">L1 快照</span>
          <NButton size="tiny" quaternary :loading="loadingL1" @click="loadL1">刷新</NButton>
        </div>
        <div class="aux-body">
          <template v-if="l1Snapshot">
            <p class="meta-line">conv={{ l1Snapshot.convId }} · near={{ l1Snapshot.nearN }} · mid={{ l1Snapshot.midN }}</p>
            <p class="section-label">far_summary</p>
            <pre class="mono-block">{{ l1Snapshot.farSummary || '（空）' }}</pre>
            <p class="section-label">mid_answers ({{ Object.keys(l1Snapshot.midAnswers || {}).length }})</p>
            <pre class="mono-block">{{ JSON.stringify(l1Snapshot.midAnswers || {}, null, 2) }}</pre>
          </template>
          <NEmpty v-else description="填写 convId 后查询" />
        </div>
      </section>

      <section class="aux-panel">
        <div class="panel-head">
          <span class="panel-title">L3 / 维护</span>
          <NSpace :size="6">
            <NButton size="tiny" quaternary :loading="loadingL3" @click="loadL3">状态</NButton>
            <NButton size="tiny" secondary :loading="runningGc" @click="handleGc">GC</NButton>
            <NButton size="tiny" secondary :loading="reingesting" @click="handleReingest">Reingest</NButton>
          </NSpace>
        </div>
        <div class="aux-body">
          <template v-if="l3Status">
            <p class="meta-line">enabled={{ l3Status.contextEnabled }} · collection={{ l3Status.collection }}</p>
            <p class="meta-line">{{ l3Status.note || 'Milvus count N/A' }}</p>
            <p class="meta-line">L1 行数={{ l3Status.l1RowCount }} · topK={{ l3Status.l3TopK }} · minScore={{ l3Status.l3MinScore }}</p>
          </template>
          <NEmpty v-else description="填写 userId 后查看 L3 状态" />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.context-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--sun-text);
}

.filter-bar {
  flex-shrink: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  background: var(--sun-black);
  padding: 8px 12px;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
}

.filter-input {
  width: 200px;
}

.filter-input-sm {
  width: 120px;
}

.context-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 12px;
}

.aux-layout {
  flex-shrink: 0;
  height: 220px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.list-panel,
.detail-panel,
.aux-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head,
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.panel-title,
.detail-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-count {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.detail-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, ui-monospace, monospace);
}

.list-spin {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
}

.entry-card {
  text-align: left;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--sun-text);
  padding: 10px 12px;
  cursor: pointer;
}

.entry-card:hover {
  border-color: var(--sun-border-strong, var(--sun-text-muted));
}

.entry-card.active {
  box-shadow: inset 0 0 0 1px var(--sun-accent, #7aa2f7);
}

.entry-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.entry-key {
  font-size: 13px;
  font-weight: 600;
}

.entry-value {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.entry-meta {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--sun-text-muted);
}

.list-empty,
.detail-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-scroll,
.aux-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
}

.meta-line {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--sun-text-muted);
  word-break: break-all;
}

.section-label {
  margin: 10px 0 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text);
}

.mono-block {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--sun-text);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow: auto;
}

:deep(.sun-field .n-input),
:deep(.sun-field .n-input-wrapper),
:deep(.sun-field .n-base-selection),
:deep(.sun-field .n-input-number) {
  background: var(--sun-black) !important;
}
</style>
