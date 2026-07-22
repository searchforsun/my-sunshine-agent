<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import type { SelectOption } from 'naive-ui'
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
  NTabPane,
  NTabs,
  NTag,
  useDialog,
  useMessage,
} from 'naive-ui'
import {
  PersonOutline,
  RefreshOutline,
  SearchOutline,
  TimeOutline,
} from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import TenantSelector from '../components/knowledge/TenantSelector.vue'
import CopyToggleIcon from '../components/icons/CopyToggleIcon.vue'
import {
  getContextL1,
  getContextL3Status,
  listContextConversations,
  listContextL2,
  listContextL3Entries,
  reingestContextL3,
  runContextL3Gc,
  updateContextL2,
  voidContextL2,
  type ConversationSummary,
  type L1Snapshot,
  type L1WindowRow,
  type L2StateEntry,
  type L3Entry,
  type L3Status,
} from '../api/contextAdmin'
import { listAuthUsers } from '../api/auth'
import type { TenantId } from '../api/tenants'
import { useAuthStore } from '../stores/authStore'
import { copyText } from '../utils/stream-markdown/clipboard'
import '../utils/stream-markdown/styles.css'
import {
  useContextRouteState,
  type ContextTab,
} from '../composables/useContextRouteState'

const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()
const routeState = useContextRouteState()

const filterTenantId = ref<TenantId>(
  (routeState.readTenant() || auth.user?.tenantId || 'default') as TenantId,
)
const filterUserId = ref(routeState.readUser() || auth.user?.userId || '')
const activeTab = ref<ContextTab>(routeState.readTab())
const routeReady = ref(false)

const authUsers = ref<Array<{ userId: string; username: string; nickname: string }>>([])
const conversations = ref<ConversationSummary[]>([])
const convSearch = ref('')
const selectedConvId = ref<string | null>(routeState.readConv())

const loadingUsers = ref(false)
const loadingConvs = ref(false)
const loading = ref(false)
const saving = ref(false)
const voiding = ref(false)
const loadingL1 = ref(false)
const loadingL3 = ref(false)
const runningGc = ref(false)
const reingesting = ref(false)

const entries = ref<L2StateEntry[]>([])
const selectedL2Id = ref<string | null>(null)
const l1Snapshot = ref<L1Snapshot | null>(null)
const l3Status = ref<L3Status | null>(null)
const l3Entries = ref<L3Entry[]>([])
const expandedL3Key = ref<string | null>(null)

const editForm = ref({
  stateValue: '',
  confidence: 0.75,
  status: 'active',
})

const KIND_META: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  profile: { label: '画像', type: 'info' },
  preference: { label: '偏好', type: 'success' },
  goal: { label: '目标', type: 'info' },
  agreement: { label: '约定', type: 'warning' },
  constraint: { label: '限制', type: 'error' },
  fact: { label: '事实', type: 'default' },
  decision: { label: '方案', type: 'success' },
}

const STATUS_LABEL: Record<string, string> = {
  active: '生效',
  superseded: '已覆盖',
  void: '已作废',
  conflict: '矛盾',
}

const statusOptions = [
  { label: '生效', value: 'active' },
  { label: '已覆盖', value: 'superseded' },
  { label: '已作废', value: 'void' },
  { label: '矛盾', value: 'conflict' },
]

/** L2 列表状态过滤；null / 清空 = 全部 */
const l2StatusFilterOptions = [...statusOptions]

const l2Search = ref('')
const l2StatusFilter = ref<string | null>(null)

const filteredConversations = computed(() => {
  const q = convSearch.value.trim().toLowerCase()
  if (!q) return conversations.value
  return conversations.value.filter(c => {
    const title = (c.title || '新对话').toLowerCase()
    return title.includes(q) || c.id.toLowerCase().includes(q)
  })
})

const filteredL2Entries = computed(() => {
  const q = l2Search.value.trim().toLowerCase()
  const st = l2StatusFilter.value
  return entries.value.filter(e => {
    if (st && e.status !== st) return false
    if (!q) return true
    const kind = (e.kind || '').toLowerCase()
    const kindZh = (KIND_META[e.kind]?.label || '').toLowerCase()
    const key = (e.stateKey || '').toLowerCase()
    const value = (e.stateValue || '').toLowerCase()
    return kind.includes(q) || kindZh.includes(q) || key.includes(q) || value.includes(q)
  })
})

const userOptions = computed(() =>
  authUsers.value.map(u => {
    const name = (u.nickname || u.username || '').trim()
    return {
      label: name || u.userId,
      value: u.userId,
    }
  }),
)

const userSelectRenderLabel = (option: SelectOption) =>
  h('span', { class: 'select-label' }, [
    h(NIcon, { component: PersonOutline, size: 14 }),
    h('span', null, String(option.label ?? option.value ?? '选择用户')),
  ])

const selectedL2 = computed(() =>
  entries.value.find(e => e.id === selectedL2Id.value) ?? null,
)

const selectedConv = computed(() =>
  conversations.value.find(c => c.id === selectedConvId.value) ?? null,
)

const l1Rows = computed(() => l1Snapshot.value?.rows || [])

const expandedL1Key = ref<string | null>(null)
const copiedL2Key = ref<string | null>(null)
let copyL2ResetTimer: ReturnType<typeof setTimeout> | null = null

const isFormDirty = computed(() => {
  const e = selectedL2.value
  if (!e) return false
  return editForm.value.stateValue !== e.stateValue
    || editForm.value.confidence !== e.confidence
    || editForm.value.status !== e.status
})

const refreshing = computed(() =>
  loading.value || loadingConvs.value || loadingL1.value || loadingL3.value,
)

function kindMeta(kind: string) {
  return KIND_META[kind] || { label: kind, type: 'default' as const }
}

function statusLabel(status: string) {
  return STATUS_LABEL[status] || status
}

function statusType(status: string): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'active') return 'success'
  if (status === 'superseded' || status === 'conflict') return 'warning'
  if (status === 'void') return 'error'
  return 'default'
}

function bandLabel(band: string) {
  if (band === 'near') return '近'
  if (band === 'mid') return '中'
  if (band === 'far') return '远'
  return band
}

function rowTag(row: L1WindowRow) {
  if (row.band === 'far') return '远'
  return `${bandLabel(row.band)} #${row.index}`
}

function l1RowKey(row: L1WindowRow, i: number) {
  return `${row.band}-${row.index}-${i}`
}

function toggleL1Expand(key: string) {
  expandedL1Key.value = expandedL1Key.value === key ? null : key
}

function formatTime(iso?: string | null) {
  if (!iso) return '—'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return iso
  return new Date(t).toLocaleString()
}

async function copyL2Field(key: string, text?: string | null) {
  const v = (text || '').trim()
  if (!v) {
    message.warning('无可复制内容')
    return
  }
  const ok = await copyText(v)
  if (!ok) {
    message.error('复制失败')
    return
  }
  copiedL2Key.value = key
  if (copyL2ResetTimer) clearTimeout(copyL2ResetTimer)
  copyL2ResetTimer = setTimeout(() => {
    if (copiedL2Key.value === key) copiedL2Key.value = null
  }, 2000)
}

function syncEditForm(entry: L2StateEntry | null) {
  if (!entry) return
  copiedL2Key.value = null
  editForm.value = {
    stateValue: entry.stateValue ?? '',
    confidence: entry.confidence ?? 0,
    status: entry.status ?? 'active',
  }
}

function selectL2(id: string) {
  selectedL2Id.value = id
  syncEditForm(entries.value.find(e => e.id === id) ?? null)
}

/** 当前选中不在过滤结果内时，改选第一条或清空 */
function ensureL2Selection() {
  const list = filteredL2Entries.value
  if (!list.length) {
    selectedL2Id.value = null
    syncEditForm(null)
    return
  }
  if (!selectedL2Id.value || !list.some(e => e.id === selectedL2Id.value)) {
    selectL2(list[0].id)
  }
}

function pushRoute() {
  routeState.syncQuery({
    tenant: filterTenantId.value || null,
    user: filterUserId.value.trim() || null,
    conv: selectedConvId.value,
    tab: activeTab.value,
  })
}

function pickFirstUser() {
  const preferred = filterUserId.value.trim()
  if (preferred && authUsers.value.some(u => u.userId === preferred)) {
    return
  }
  filterUserId.value = authUsers.value[0]?.userId || ''
}

function pickConversation() {
  const preferred = selectedConvId.value
  if (preferred && conversations.value.some(c => c.id === preferred)) {
    return
  }
  selectedConvId.value = conversations.value[0]?.id ?? null
  if (!selectedConvId.value) {
    l1Snapshot.value = null
  }
}

async function loadUsers() {
  loadingUsers.value = true
  try {
    authUsers.value = await listAuthUsers(filterTenantId.value || 'default')
    pickFirstUser()
  } catch (e) {
    authUsers.value = []
    message.error(e instanceof Error ? e.message : '加载用户失败')
  } finally {
    loadingUsers.value = false
  }
}

async function loadConversations() {
  const userId = filterUserId.value.trim()
  if (!userId) {
    conversations.value = []
    selectedConvId.value = null
    l1Snapshot.value = null
    return
  }
  loadingConvs.value = true
  try {
    conversations.value = await listContextConversations(
      userId,
      filterTenantId.value || 'default',
    )
    pickConversation()
  } catch (e) {
    conversations.value = []
    selectedConvId.value = null
    l1Snapshot.value = null
    message.error(e instanceof Error ? e.message : '加载会话失败')
  } finally {
    loadingConvs.value = false
  }
}

async function loadL2() {
  const userId = filterUserId.value.trim()
  if (!userId) {
    entries.value = []
    selectedL2Id.value = null
    return
  }
  loading.value = true
  try {
    entries.value = await listContextL2(userId, filterTenantId.value || 'default')
    if (selectedL2Id.value && !entries.value.some(e => e.id === selectedL2Id.value)) {
      selectedL2Id.value = null
    }
    ensureL2Selection()
    if (selectedL2Id.value) {
      syncEditForm(selectedL2.value)
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载 L2 失败')
  } finally {
    loading.value = false
  }
}

async function loadL1(convId?: string) {
  const id = (convId || selectedConvId.value || '').trim()
  expandedL1Key.value = null
  if (!id) {
    l1Snapshot.value = null
    return
  }
  loadingL1.value = true
  try {
    l1Snapshot.value = await getContextL1(id)
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
    l3Status.value = null
    return
  }
  loadingL3.value = true
  try {
    l3Status.value = await getContextL3Status(userId, filterTenantId.value || 'default')
  } catch (e) {
    l3Status.value = null
    message.error(e instanceof Error ? e.message : '加载 L3 状态失败')
  } finally {
    loadingL3.value = false
  }
}

async function loadL3Entries(convId?: string) {
  const id = (convId || selectedConvId.value || '').trim()
  expandedL3Key.value = null
  if (!id) {
    l3Entries.value = []
    return
  }
  loadingL3.value = true
  try {
    l3Entries.value = await listContextL3Entries(id)
  } catch (e) {
    l3Entries.value = []
    message.error(e instanceof Error ? e.message : '加载 L3 索引失败')
  } finally {
    loadingL3.value = false
  }
}

function l3RowKey(entry: L3Entry, i: number) {
  return `${entry.msgId}-${entry.chunkIndex}-${i}`
}

function toggleL3Expand(key: string) {
  expandedL3Key.value = expandedL3Key.value === key ? null : key
}

function l3RoleLabel(role?: string) {
  if (role === 'user') return 'User'
  if (role === 'assistant') return 'Assistant'
  return 'Chunk'
}

async function refreshAll() {
  await Promise.all([loadConversations(), loadL2(), loadL3()])
  if (selectedConvId.value) {
    await Promise.all([loadL1(selectedConvId.value), loadL3Entries(selectedConvId.value)])
  } else {
    l3Entries.value = []
  }
}

async function selectConversation(id: string) {
  selectedConvId.value = id
  await Promise.all([loadL1(id), loadL3Entries(id)])
}

async function handleSave() {
  if (!selectedL2.value || !isFormDirty.value) return
  saving.value = true
  try {
    const updated = await updateContextL2(selectedL2.value.id, {
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

function handleVoid() {
  if (!selectedL2.value) return
  const key = selectedL2.value.stateKey
  dialog.warning({
    class: 'sunshine-dialog',
    title: '作废状态',
    content: `确定作废「${kindMeta(selectedL2.value.kind).label} / ${key}」吗？作废后不再注入上下文。`,
    positiveText: '作废',
    negativeText: '取消',
    positiveButtonProps: { type: 'error', size: 'medium' },
    negativeButtonProps: { ghost: false, quaternary: true, size: 'medium' },
    onPositiveClick: () => doVoid(),
  })
}

async function doVoid() {
  if (!selectedL2.value) return
  voiding.value = true
  try {
    const updated = await voidContextL2(selectedL2.value.id)
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
    message.success(r.message || '清理完成')
    await loadL3()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '清理失败')
  } finally {
    runningGc.value = false
  }
}

async function handleReingest() {
  const convId = selectedConvId.value?.trim()
  if (!convId) {
    message.warning('请先在左侧选择会话')
    return
  }
  reingesting.value = true
  try {
    const r = await reingestContextL3(convId)
    message.success(r.message || `已提交重建，共 ${r.ingested} 条`)
    // upsert 异步入向量库，稍后再拉列表
    await new Promise(r => setTimeout(r, 1200))
    await loadL3Entries(convId)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重建失败')
  } finally {
    reingesting.value = false
  }
}

watch(filterTenantId, async () => {
  if (!routeReady.value) return
  selectedConvId.value = null
  l1Snapshot.value = null
  selectedL2Id.value = null
  convSearch.value = ''
  l2Search.value = ''
  l2StatusFilter.value = null
  await loadUsers()
  await refreshAll()
})

watch(filterUserId, async () => {
  if (!routeReady.value) return
  selectedConvId.value = null
  l1Snapshot.value = null
  selectedL2Id.value = null
  convSearch.value = ''
  l2Search.value = ''
  l2StatusFilter.value = null
  await refreshAll()
})

watch([l2Search, l2StatusFilter], () => {
  if (!entries.value.length) return
  ensureL2Selection()
})

watch(
  [filterTenantId, filterUserId, selectedConvId, activeTab],
  () => {
    if (!routeReady.value) return
    pushRoute()
  },
)

onMounted(async () => {
  await loadUsers()
  await refreshAll()
  routeReady.value = true
  pushRoute()
})
</script>

<template>
  <div class="context-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>上下文</h2>
      </div>
      <NSpace :size="8" align="center" class="page-header-actions">
        <span class="context-label">租户</span>
        <TenantSelector v-model:model-value="filterTenantId" />
        <span class="context-sep" aria-hidden="true" />
        <span class="context-label">用户</span>
        <NSelect
          v-model:value="filterUserId"
          class="sun-field filter-select"
          :options="userOptions"
          :render-label="userSelectRenderLabel"
          :loading="loadingUsers"
          :consistent-menu-width="false"
          filterable
          clearable
          placeholder="选择用户"
          size="small"
        />
        <NButton
          round
          type="primary"
          class="action-btn"
          :loading="refreshing"
          @click="refreshAll"
        >
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <div class="context-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">会话</span>
          <NTag :bordered="false" size="tiny" round>{{ filteredConversations.length }}</NTag>
        </div>
        <div class="list-search">
          <NInput
            v-model:value="convSearch"
            placeholder="搜索标题或 ID…"
            size="small"
            round
            clearable
            class="search-input"
            :disabled="loadingConvs"
          >
            <template #prefix>
              <NIcon :component="SearchOutline" :size="14" />
            </template>
          </NInput>
        </div>
        <NSpin :show="loadingConvs" class="list-spin">
          <div v-if="filteredConversations.length" class="entry-list">
            <button
              v-for="item in filteredConversations"
              :key="item.id"
              type="button"
              class="conv-row"
              :class="{ active: item.id === selectedConvId }"
              :title="item.id"
              @click="selectConversation(item.id)"
            >
              <span class="conv-title">{{ item.title || '新对话' }}</span>
              <span class="conv-time">
                <NIcon :component="TimeOutline" :size="12" />
                {{ formatTime(item.updatedAt) }}
              </span>
            </button>
          </div>
          <div v-else class="empty-wrap">
            <NEmpty
              size="small"
              :description="conversations.length && convSearch.trim() ? '无匹配会话' : '该用户暂无会话'"
            />
          </div>
        </NSpin>
      </aside>

      <main class="detail-panel">
        <NTabs v-model:value="activeTab" type="line" size="small" class="layer-tabs">
          <NTabPane name="l1" tab="L1 会话快照">
            <template v-if="!selectedConv">
              <div class="empty-wrap fill">
                <NEmpty size="small" description="暂无会话" />
              </div>
            </template>
            <NSpin v-else :show="loadingL1" class="tab-spin">
              <div v-if="l1Snapshot" class="l1-body">
                <div v-if="l1Rows.length" class="l1-row-list">
                  <article
                    v-for="(row, i) in l1Rows"
                    :key="l1RowKey(row, i)"
                    class="l1-row"
                    :class="{ expanded: expandedL1Key === l1RowKey(row, i) }"
                    :data-band="row.band"
                    role="button"
                    tabindex="0"
                    @click="toggleL1Expand(l1RowKey(row, i))"
                    @keydown.enter.prevent="toggleL1Expand(l1RowKey(row, i))"
                    @keydown.space.prevent="toggleL1Expand(l1RowKey(row, i))"
                  >
                    <header class="l1-row-head">
                      <span class="l1-band-tag" :data-band="row.band">{{ rowTag(row) }}</span>
                      <span
                        v-if="row.band === 'mid' && row.assistantSummarized"
                        class="l1-band-tag soft"
                        data-band="mid"
                      >摘要</span>
                      <span class="l1-row-time">{{ formatTime(row.at || undefined) }}</span>
                    </header>
                    <div class="l1-row-scroll">
                      <template v-if="row.band === 'far'">
                        <div class="l1-role-block">
                          <span class="l1-role">摘要</span>
                          <div class="l1-role-text">{{ row.assistantText || '（空）' }}</div>
                        </div>
                      </template>
                      <template v-else>
                        <div class="l1-role-block">
                          <span class="l1-role">User</span>
                          <div class="l1-role-text">{{ row.userText || '（空）' }}</div>
                        </div>
                        <div class="l1-role-block">
                          <span class="l1-role">Assistant</span>
                          <div class="l1-role-text">{{ row.assistantText || '（空）' }}</div>
                        </div>
                      </template>
                    </div>
                  </article>
                </div>
                <div v-else class="empty-wrap fill">
                  <NEmpty size="small" description="暂无会话" />
                </div>
              </div>
              <div v-else class="empty-wrap fill">
                <NEmpty size="small" description="暂无会话" />
              </div>
            </NSpin>
          </NTabPane>

          <NTabPane name="l2" tab="L2 用户状态">
            <template v-if="!selectedConv">
              <div class="empty-wrap fill">
                <NEmpty size="small" description="请选择会话" />
              </div>
            </template>
            <NSpin v-else :show="loading" class="tab-spin">
              <div v-if="!entries.length" class="empty-wrap fill">
                <NEmpty size="small" description="暂无跨会话状态（对话后异步抽取）" />
              </div>
              <div v-else class="l2-layout">
                <div class="l2-list-col">
                  <div class="l2-filters">
                    <NInput
                      v-model:value="l2Search"
                      placeholder="筛选 key / 值…"
                      size="small"
                      round
                      clearable
                      class="search-input"
                    >
                      <template #prefix>
                        <NIcon :component="SearchOutline" :size="14" />
                      </template>
                    </NInput>
                    <NSelect
                      v-model:value="l2StatusFilter"
                      class="sun-field l2-status-filter"
                      size="small"
                      clearable
                      placeholder="全部状态"
                      :options="l2StatusFilterOptions"
                    />
                  </div>
                  <div v-if="filteredL2Entries.length" class="l2-list">
                    <button
                      v-for="item in filteredL2Entries"
                      :key="item.id"
                      type="button"
                      class="l2-row"
                      :class="{ active: item.id === selectedL2Id }"
                      @click="selectL2(item.id)"
                    >
                      <div class="l2-row-head">
                        <NTag size="tiny" :type="kindMeta(item.kind).type" :bordered="false" round>
                          {{ kindMeta(item.kind).label }}
                        </NTag>
                        <NTag size="tiny" :type="statusType(item.status)" :bordered="false">
                          {{ statusLabel(item.status) }}
                        </NTag>
                      </div>
                      <span class="l2-key">{{ item.stateKey }}</span>
                      <p class="l2-value">{{ item.stateValue }}</p>
                    </button>
                  </div>
                  <div v-else class="empty-wrap l2-filter-empty">
                    <NEmpty size="small" description="无匹配条目" />
                  </div>
                </div>
                <div v-if="selectedL2" class="edit-pane">
                  <div class="edit-head">
                    <div class="edit-title-block">
                      <div class="edit-title-row">
                        <NTag size="small" :type="kindMeta(selectedL2.kind).type" :bordered="false" round>
                          {{ kindMeta(selectedL2.kind).label }}
                        </NTag>
                        <h3 class="detail-title">{{ selectedL2.stateKey }}</h3>
                      </div>
                    </div>
                    <NSpace :size="8">
                      <NButton
                        size="small"
                        secondary
                        :loading="voiding"
                        :disabled="selectedL2.status === 'void'"
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
                  <NForm class="detail-form" label-placement="top" :show-feedback="false">
                    <NFormItem label="值">
                      <NInput
                        v-model:value="editForm.stateValue"
                        class="sun-field sun-field-grow"
                        type="textarea"
                        :autosize="{ minRows: 3, maxRows: 10 }"
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
                  </NForm>
                  <div class="meta-block">
                    <div class="meta-row">
                      <span class="meta-label">条目 ID</span>
                      <div class="meta-value-row">
                        <code class="meta-id">{{ selectedL2.id }}</code>
                        <button
                          type="button"
                          class="copy-btn smd-toolbtn"
                          :title="copiedL2Key === 'id' ? '已复制' : '复制'"
                          @click="copyL2Field('id', selectedL2.id)"
                        >
                          <CopyToggleIcon :copied="copiedL2Key === 'id'" />
                        </button>
                      </div>
                    </div>
                    <div class="meta-row">
                      <span class="meta-label">溯源消息</span>
                      <div class="meta-value-row">
                        <code class="meta-id">{{ selectedL2.sourceMsgId || '—' }}</code>
                        <button
                          v-if="selectedL2.sourceMsgId"
                          type="button"
                          class="copy-btn smd-toolbtn"
                          :title="copiedL2Key === 'source' ? '已复制' : '复制'"
                          @click="copyL2Field('source', selectedL2.sourceMsgId)"
                        >
                          <CopyToggleIcon :copied="copiedL2Key === 'source'" />
                        </button>
                      </div>
                    </div>
                    <div class="meta-row">
                      <span class="meta-label">过期时间</span>
                      <span class="meta-text">{{ formatTime(selectedL2.expiresAt) }}</span>
                    </div>
                    <div class="meta-row">
                      <span class="meta-label">更新时间</span>
                      <span class="meta-text">{{ formatTime(selectedL2.updatedAt) }}</span>
                    </div>
                  </div>
                </div>
                <div v-else class="empty-wrap">
                  <NEmpty size="small" description="选择左侧条目进行编辑" />
                </div>
              </div>
            </NSpin>
          </NTabPane>

          <NTabPane name="l3" tab="L3 历史索引">
            <div class="ops-bar">
              <NButton size="small" secondary :loading="runningGc" @click="handleGc">
                清理过期索引
              </NButton>
              <NButton
                size="small"
                type="primary"
                class="action-btn"
                :loading="reingesting"
                :disabled="!selectedConvId"
                @click="handleReingest"
              >
                重新会话索引
              </NButton>
            </div>
            <template v-if="!selectedConv">
              <div class="empty-wrap fill">
                <NEmpty size="small" description="请选择会话" />
              </div>
            </template>
            <NSpin v-else :show="loadingL3" class="tab-spin">
              <div v-if="l3Entries.length" class="l3-body">
                <div class="l1-row-list">
                  <article
                    v-for="(entry, i) in l3Entries"
                    :key="l3RowKey(entry, i)"
                    class="l1-row l3-row"
                    :class="{ expanded: expandedL3Key === l3RowKey(entry, i) }"
                    role="button"
                    tabindex="0"
                    @click="toggleL3Expand(l3RowKey(entry, i))"
                    @keydown.enter.prevent="toggleL3Expand(l3RowKey(entry, i))"
                    @keydown.space.prevent="toggleL3Expand(l3RowKey(entry, i))"
                  >
                    <header class="l1-row-head">
                      <span class="l1-band-tag" data-band="near">{{ l3RoleLabel(entry.role) }}</span>
                      <span class="l1-band-tag soft" data-band="mid">#{{ entry.chunkIndex }}</span>
                      <span class="l1-row-time">生成 {{ formatTime(entry.createdAt) }}</span>
                      <span class="l1-row-time">
                        过期 {{ entry.expiresAt ? formatTime(entry.expiresAt) : '无硬过期' }}
                      </span>
                    </header>
                    <div class="l1-row-scroll">
                      <div class="l1-role-text">{{ entry.content || '（空）' }}</div>
                    </div>
                  </article>
                </div>
              </div>
              <div v-else class="empty-wrap fill">
                <NEmpty
                  size="small"
                  description="该会话尚无 L3 索引（可点「重新会话索引」）"
                />
              </div>
            </NSpin>
          </NTabPane>
        </NTabs>
      </main>
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
  gap: 12px;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--sun-text);
  line-height: 1.2;
}

.select-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.filter-select {
  width: 220px;
}

.context-label {
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  flex-shrink: 0;
}

.context-sep {
  width: 1px;
  height: 16px;
  background: var(--sun-border);
  flex-shrink: 0;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}

.context-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 12px;
}

.list-panel,
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 14px 0;
  flex-shrink: 0;
}

.panel-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.list-search {
  padding: 10px 12px;
  flex-shrink: 0;
}

.search-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.list-spin,
.tab-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}

.tab-spin :deep(.n-spin-container),
.tab-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
}

.conv-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-align: left;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--sun-text);
  padding: 10px 12px;
  cursor: pointer;
}

.conv-row:hover {
  border-color: var(--sun-border-strong, var(--sun-text-muted));
}

.conv-row.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.conv-title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--sun-text-muted);
}

.layer-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 14px 14px;
}

.layer-tabs :deep(.n-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.layer-tabs :deep(.n-tabs-nav) {
  padding-top: 6px;
  flex-shrink: 0;
}

.layer-tabs :deep(.n-tabs-tab) {
  font-size: 13px;
  padding: 8px 0;
}

.layer-tabs :deep(.n-tabs-bar) {
  height: 2px;
}

.layer-tabs :deep(.n-tabs-pane-wrapper),
.layer-tabs :deep(.n-tabs-content),
.layer-tabs :deep(.n-tab-pane) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.ops-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.l3-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 0 8px;
}

.l3-row .l1-row-head {
  flex-wrap: wrap;
}

.l2-layout {
  display: grid;
  grid-template-columns: minmax(200px, 260px) 1fr;
  gap: 12px;
  min-height: 0;
  height: 100%;
}

.l2-list-col {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.l2-filters {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex-shrink: 0;
}

.l2-status-filter {
  width: 100%;
}

.l2-list {
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}

.l2-filter-empty {
  flex: 1;
  min-height: 120px;
}

.l2-row {
  text-align: left;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--sun-text);
  padding: 10px 12px;
  cursor: pointer;
}

.l2-row:hover {
  border-color: var(--sun-border-strong, var(--sun-text-muted));
}

.l2-row.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.l2-row-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.l2-key {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
}

.l2-value {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.edit-pane {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  overflow: auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.edit-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--sun-border);
}

.edit-title-block {
  min-width: 0;
}

.edit-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.detail-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.detail-form :deep(.n-form-item) {
  margin-bottom: 14px;
}

.detail-form :deep(.n-form-item-label) {
  padding-bottom: 6px !important;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px 16px;
}

.meta-block {
  margin-top: 8px;
  padding-top: 14px;
  border-top: 1px solid var(--sun-border);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 8px 12px;
  align-items: start;
  font-size: 12px;
}

.meta-label {
  color: var(--sun-text-muted);
  line-height: 22px;
}

.meta-value-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  min-width: 0;
}

.meta-id {
  flex: 1;
  min-width: 0;
  margin: 0;
  padding: 2px 0;
  font-family: var(--sun-font-mono, ui-monospace, monospace);
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text);
  word-break: break-all;
  white-space: pre-wrap;
}

.meta-text {
  color: var(--sun-text);
  line-height: 22px;
}

.copy-btn {
  flex-shrink: 0;
  margin-top: 0;
  color: var(--sun-text-muted);
}

.copy-btn:hover {
  color: var(--sun-text);
}

.detail-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.l1-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 0 8px;
}

.l1-row-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.l1-row {
  height: 220px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  background: var(--sun-black);
  cursor: pointer;
  transition: height 0.18s ease, border-color 0.15s ease;
}

.l1-row:hover {
  border-color: var(--sun-text-muted);
}

.l1-row.expanded {
  height: 480px;
}

.l1-row-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.l1-band-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid transparent;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}

.l1-band-tag[data-band='near'] {
  color: #5b6b7a;
  background: color-mix(in srgb, #7a8fa3 18%, transparent);
  border-color: color-mix(in srgb, #7a8fa3 35%, transparent);
}

.l1-band-tag[data-band='mid'] {
  color: #6a6b55;
  background: color-mix(in srgb, #9aa06e 18%, transparent);
  border-color: color-mix(in srgb, #9aa06e 35%, transparent);
}

.l1-band-tag[data-band='far'] {
  color: #6b5d6e;
  background: color-mix(in srgb, #9a849e 18%, transparent);
  border-color: color-mix(in srgb, #9a849e 35%, transparent);
}

.l1-band-tag.soft {
  font-weight: 500;
}

.l1-row-time {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.l1-row-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.l1-role-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.l1-role {
  font-size: 11px;
  font-weight: 600;
  color: var(--sun-text-muted);
}

.l1-role-text {
  font-size: 13px;
  line-height: 1.55;
  color: var(--sun-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.l1-empty-hint {
  margin: 0;
  font-size: 13px;
  color: var(--sun-text-muted);
}

.meta-line {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--sun-text-muted);
  word-break: break-all;
}

.empty-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 140px;
  width: 100%;
}

.empty-wrap.fill {
  min-height: 0;
  height: 100%;
  align-self: stretch;
}

:deep(.sun-field .n-input),
:deep(.sun-field .n-input-wrapper),
:deep(.sun-field .n-base-selection),
:deep(.sun-field .n-input-number) {
  background: var(--sun-black) !important;
}

@media (max-width: 960px) {
  .context-layout {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(200px, 40%) 1fr;
  }

  .l2-layout {
    grid-template-columns: 1fr;
  }
}
</style>
