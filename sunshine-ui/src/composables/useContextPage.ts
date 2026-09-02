import { computed, h, onMounted, reactive, ref, watch, type ComputedRef, type Ref } from 'vue'
import type { SelectOption } from 'naive-ui'
import { NIcon, useDialog, useMessage } from 'naive-ui'
import { PersonOutline } from '@vicons/ionicons5'
import {
  getContextL1,
  getContextL3Status,
  getH1Notebook,
  getTaskBoardSnapshot,
  listContextConversations,
  listContextL2,
  listContextL3Entries,
  reingestContextL3,
  runContextL3Gc,
  updateContextL2,
  voidContextL2,
  type ConversationSummary,
  type H1Notebook,
  type L1Snapshot,
  type L1WindowRow,
  type L2StateEntry,
  type L3Entry,
  type L3Status,
  type TaskBoardSnapshot,
} from '../api/contextAdmin'
import { searchTaskHistory, type TaskHistoryHit } from '../api/ragAdmin'
import { listAuthUsers } from '../api/auth'
import type { TenantId } from '../api/tenants'
import { useAuthStore } from '../stores/authStore'
import { copyText } from '../utils/stream-markdown/clipboard'
import {
  useContextRouteState,
  type ContextKindTab,
  type ContextTab,
  type ContextTaskTab,
} from './useContextRouteState'
import {
  KIND_META,
  formatTime,
  kindMeta,
  l3RoleLabel,
  rowTag,
  statusLabel,
  statusType,
} from '../components/context/contextLabels'

export const CONTEXT_PAGE_KEY = Symbol('contextPage')

export function useContextPage() {
  const message = useMessage()
  const dialog = useDialog()
  const auth = useAuthStore()
  const routeState = useContextRouteState()

  const filterTenantId = ref<TenantId>(
    (routeState.readTenant() || auth.user?.tenantId || 'default') as TenantId,
  )
  const filterUserId = ref(routeState.readUser() || auth.user?.userId || '')
  /** 上侧 kind Tab：对话 | 任务（对齐智能体管理页；任务上下文非 L1/L2/L3） */
  const kindTab = ref<ContextKindTab>(routeState.readKind())
  const activeTab = ref<ContextTab>(routeState.readTab())
  /** 任务分层上下文 tab（对齐对话 L1/L2/L3 分层结构） */
  const taskTab = ref<ContextTaskTab>(routeState.readTaskTab())
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

  /** 任务分层上下文（T0 / H1 / L3 可观测） */
  const taskBoardSnapshot = ref<TaskBoardSnapshot | null>(null)
  const h1Notebook = ref<H1Notebook | null>(null)
  const taskHistoryHits = ref<TaskHistoryHit[]>([])
  const loadingTaskBoard = ref(false)
  const loadingH1 = ref(false)
  const loadingTaskHistory = ref(false)
  const taskHistoryQuery = ref('')
  const expandedTaskHistoryKey = ref<string | null>(null)

  const editForm = ref({
    stateValue: '',
    confidence: 0.75,
    status: 'active',
  })

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
    // 上侧 kind Tab 过滤：会话 kind 缺省 chat
    const list = conversations.value.filter(c => (c.kind || 'chat') === kindTab.value)
    const q = convSearch.value.trim().toLowerCase()
    if (!q) return list
    return list.filter(c => {
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
    loading.value || loadingConvs.value || loadingL1.value || loadingL3.value
      || loadingTaskBoard.value || loadingH1.value,
  )

  function l1RowKey(row: L1WindowRow, i: number) {
    return `${row.band}-${row.index}-${i}`
  }

  function toggleL1Expand(key: string) {
    expandedL1Key.value = expandedL1Key.value === key ? null : key
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
      kind: kindTab.value,
      tenant: filterTenantId.value || null,
      user: filterUserId.value.trim() || null,
      conv: selectedConvId.value,
      tab: activeTab.value,
      taskTab: taskTab.value,
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
    // 仅当首选会话落在当前 kindTab 过滤后的列表内才保留（列表已按 kind 隔离，
    // 避免从 URL 恢复或默认选中落到 task 会话，导致「对话」L3 历史索引展示 task 过程）
    if (preferred && filteredConversations.value.some(c => c.id === preferred)) {
      return
    }
    selectedConvId.value = filteredConversations.value[0]?.id ?? null
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

  async function loadTaskBoard(convId?: string) {
    const id = (convId || selectedConvId.value || '').trim()
    if (!id) {
      taskBoardSnapshot.value = null
      return
    }
    loadingTaskBoard.value = true
    try {
      taskBoardSnapshot.value = await getTaskBoardSnapshot(id)
    } catch (e) {
      taskBoardSnapshot.value = null
    } finally {
      loadingTaskBoard.value = false
    }
  }

  async function loadH1(convId?: string) {
    const id = (convId || selectedConvId.value || '').trim()
    if (!id) {
      h1Notebook.value = null
      return
    }
    loadingH1.value = true
    try {
      h1Notebook.value = await getH1Notebook(id)
    } catch (e) {
      h1Notebook.value = null
    } finally {
      loadingH1.value = false
    }
  }

  async function runTaskHistorySearch(convId?: string) {
    const id = (convId || selectedConvId.value || '').trim()
    const query = taskHistoryQuery.value.trim()
    const userId = filterUserId.value.trim()
    if (!userId || !query) {
      taskHistoryHits.value = []
      expandedTaskHistoryKey.value = null
      return
    }
    expandedTaskHistoryKey.value = null
    loadingTaskHistory.value = true
    try {
      // 按当前选中的 task 会话检索其 L3 body/process 段落
      taskHistoryHits.value = await searchTaskHistory(filterTenantId.value || 'default', {
        userId,
        query,
        convId: id,
      })
    } catch (e) {
      taskHistoryHits.value = []
      message.error(e instanceof Error ? e.message : '任务检索失败')
    } finally {
      loadingTaskHistory.value = false
    }
  }

  function l3RowKey(entry: L3Entry, i: number) {
    return `${entry.msgId}-${entry.chunkIndex}-${i}`
  }

  function toggleL3Expand(key: string) {
    expandedL3Key.value = expandedL3Key.value === key ? null : key
  }

  function taskHistoryRowKey(hit: TaskHistoryHit, i: number) {
    return `${hit.convId}-${hit.msgId}-${i}`
  }

  function toggleTaskHistoryExpand(key: string) {
    expandedTaskHistoryKey.value = expandedTaskHistoryKey.value === key ? null : key
  }

  async function refreshAll() {
    await Promise.all([loadConversations(), loadL2(), loadL3()])
    if (selectedConvId.value) {
      await Promise.all([
        loadL1(selectedConvId.value),
        loadL3Entries(selectedConvId.value),
        loadTaskBoard(selectedConvId.value),
        loadH1(selectedConvId.value),
      ])
    } else {
      l3Entries.value = []
      taskBoardSnapshot.value = null
      h1Notebook.value = null
    }
  }

  async function selectConversation(id: string) {
    selectedConvId.value = id
    taskHistoryQuery.value = ''
    taskHistoryHits.value = []
    expandedTaskHistoryKey.value = null
    await Promise.all([
      loadL1(id),
      loadL3Entries(id),
      loadTaskBoard(id),
      loadH1(id),
    ])
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

  /** 上侧 kind Tab：切换后选中会话落在该 kind 内第一条，并重置分层 tab */
  watch(kindTab, async () => {
    if (!routeReady.value) return
    selectedConvId.value = null
    l1Snapshot.value = null
    selectedL2Id.value = null
    activeTab.value = 'l1'
    taskTab.value = 'w0'
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
    [kindTab, filterTenantId, filterUserId, selectedConvId, activeTab, taskTab],
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

  // reactive 包装：子组件 inject 后内嵌 Ref/ComputedRef 自动解包（否则 NSpin :show 恒为真）
  return reactive({
    filterTenantId,
    filterUserId,
    kindTab,
    activeTab,
    taskTab,
    authUsers,
    conversations,
    convSearch,
    selectedConvId,
    loadingUsers,
    loadingConvs,
    loading,
    saving,
    voiding,
    loadingL1,
    loadingL3,
    runningGc,
    reingesting,
    entries,
    selectedL2Id,
    l1Snapshot,
    l3Status,
    l3Entries,
    expandedL3Key,
    taskBoardSnapshot,
    h1Notebook,
    taskHistoryHits,
    loadingTaskBoard,
    loadingH1,
    loadingTaskHistory,
    taskHistoryQuery,
    expandedTaskHistoryKey,
    editForm,
    statusOptions,
    l2StatusFilterOptions,
    l2Search,
    l2StatusFilter,
    filteredConversations,
    filteredL2Entries,
    userOptions,
    userSelectRenderLabel,
    selectedL2,
    selectedConv,
    l1Rows,
    expandedL1Key,
    copiedL2Key,
    isFormDirty,
    refreshing,
    kindMeta,
    statusLabel,
    statusType,
    rowTag,
    l1RowKey,
    toggleL1Expand,
    formatTime,
    copyL2Field,
    selectL2,
    l3RowKey,
    toggleL3Expand,
    l3RoleLabel,
    loadTaskBoard,
    loadH1,
    runTaskHistorySearch,
    taskHistoryRowKey,
    toggleTaskHistoryExpand,
    refreshAll,
    selectConversation,
    handleSave,
    handleVoid,
    handleGc,
    handleReingest,
  })
}

/** 子组件 prop 用：模板侧按解包后的 Ref/ComputedRef 访问 */
type UnwrapPageMember<T> =
  T extends Ref<infer V> ? V :
  T extends ComputedRef<infer V> ? V :
  T extends (...args: infer A) => infer R ? (...args: A) => R :
  T

type ContextPageComposable = ReturnType<typeof useContextPage>

export type ContextPageApi = {
  [K in keyof ContextPageComposable]: UnwrapPageMember<ContextPageComposable[K]>
}
