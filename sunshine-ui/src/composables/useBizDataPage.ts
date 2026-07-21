import { computed, h, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NSpace,
  useMessage,
  type DataTableColumns,
  type SelectOption,
} from 'naive-ui'
import {
  createBizRow,
  deleteBizRow,
  listBizRows,
  updateBizRow,
  type BizDomain,
  type BizTable,
} from '../api/bizData'
import { listAuthUsers } from '../api/auth'
import { ApiError } from '../api/apiError'
import {
  BIZ_DOMAINS,
  BIZ_TABLE_DEFS,
  BIZ_TENANT_ID,
  type FieldDef,
} from '../utils/bizTableSchema'

export function useBizDataPage() {
  const message = useMessage()
  const loading = ref(false)
  const saving = ref(false)
  const deleting = ref(false)
  const domain = ref<BizDomain>('finance')
  const tableKey = ref<BizTable>('expenses')
  const rows = ref<Record<string, unknown>[]>([])
  const authUsers = ref<Array<{ userId: string; username: string; nickname: string }>>([])

  const showFormModal = ref(false)
  const formMode = ref<'create' | 'edit'>('create')
  const formDraft = ref<Record<string, unknown>>({})
  const editKey = ref<Record<string, unknown> | null>(null)

  const showDeleteModal = ref(false)
  const deleteTarget = ref<Record<string, unknown> | null>(null)

  const tableDefs = computed(() => BIZ_TABLE_DEFS[domain.value])
  const currentTable = computed(
    () => tableDefs.value.find(t => t.key === tableKey.value) ?? tableDefs.value[0],
  )

  const userOptions = computed((): SelectOption[] =>
    authUsers.value.map(u => ({
      label: u.nickname?.trim() || u.username || u.userId,
      value: u.userId,
    })),
  )

  const userLabelMap = computed(() => {
    const map = new Map<string, string>()
    for (const u of authUsers.value) {
      map.set(u.userId, u.nickname?.trim() || u.username || u.userId)
    }
    return map
  })

  function rowKeyOf(row: Record<string, unknown>): string {
    const table = currentTable.value.key
    if (table === 'leave-balances') return `${row.userId}-${row.year}`
    if (table === 'attendance-months') return `${row.userId}-${row.yearMonth}`
    return String(row.id ?? '')
  }

  function formatUserCell(userId: unknown): string {
    if (userId == null || userId === '') return ''
    const id = String(userId)
    return userLabelMap.value.get(id) ?? id
  }

  const columns = computed((): DataTableColumns<Record<string, unknown>> => {
    const table = currentTable.value
    const cols: DataTableColumns<Record<string, unknown>> = table.fields.map(f => ({
      title: f.label,
      key: f.key,
      ellipsis: { tooltip: true },
      render(row) {
        const v = row[f.key]
        if (f.kind === 'user') return formatUserCell(v)
        if (v == null) return ''
        return String(v)
      },
    }))
    cols.push({
      title: '操作',
      key: '_actions',
      width: 140,
      render(row) {
        return h(
          NSpace,
          { size: 8 },
          {
            default: () => [
              h(
                NButton,
                { size: 'tiny', quaternary: true, onClick: () => openEdit(row) },
                { default: () => '编辑' },
              ),
              h(
                NButton,
                { size: 'tiny', quaternary: true, type: 'error', onClick: () => openDelete(row) },
                { default: () => '删除' },
              ),
            ],
          },
        )
      },
    })
    return cols
  })

  function emptyDraft(): Record<string, unknown> {
    const draft: Record<string, unknown> = {}
    for (const f of currentTable.value.fields) {
      if (f.key === 'tenantId') {
        draft.tenantId = BIZ_TENANT_ID
      } else if (f.kind === 'number' || f.kind === 'year') {
        draft[f.key] = null
      } else {
        draft[f.key] = null
      }
    }
    return draft
  }

  function buildBody(draft: Record<string, unknown>): Record<string, unknown> {
    const exclude = new Set(currentTable.value.excludeFromBody ?? [])
    const body: Record<string, unknown> = {}
    for (const f of currentTable.value.fields) {
      if (exclude.has(f.key)) continue
      const v = draft[f.key]
      if (f.kind === 'number' || f.kind === 'year') {
        if (v === '' || v == null) {
          body[f.key] = null
        } else {
          body[f.key] = typeof v === 'number' ? v : Number(v)
        }
      } else {
        body[f.key] = typeof v === 'string' ? v.trim() : v
      }
    }
    return body
  }

  function compositeKeyChanged(body: Record<string, unknown>): boolean {
    if (!editKey.value) return false
    for (const f of currentTable.value.fields) {
      if (!f.compositeKey) continue
      const next = body[f.key]
      const prev = editKey.value[f.key]
      if (String(next ?? '') !== String(prev ?? '')) return true
    }
    return false
  }

  async function loadAuthUsers() {
    try {
      authUsers.value = await listAuthUsers(BIZ_TENANT_ID)
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '加载用户列表失败')
      console.error(e)
      authUsers.value = []
    }
  }

  async function loadRows() {
    loading.value = true
    try {
      rows.value = await listBizRows(domain.value, currentTable.value.key, {
        tenantId: BIZ_TENANT_ID,
      })
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '加载数据失败')
      console.error(e)
      rows.value = []
    } finally {
      loading.value = false
    }
  }

  async function refresh() {
    await Promise.all([loadAuthUsers(), loadRows()])
  }

  function selectTable(key: BizTable) {
    if (tableKey.value === key) return
    tableKey.value = key
  }

  function openCreate() {
    formMode.value = 'create'
    editKey.value = null
    formDraft.value = emptyDraft()
    showFormModal.value = true
  }

  function openEdit(row: Record<string, unknown>) {
    formMode.value = 'edit'
    editKey.value = { ...row }
    const draft = emptyDraft()
    for (const f of currentTable.value.fields) {
      const v = row[f.key]
      if (f.kind === 'year' || f.kind === 'number') {
        draft[f.key] = v == null || v === '' ? null : Number(v)
      } else {
        draft[f.key] = v == null ? null : String(v)
      }
    }
    formDraft.value = draft
    showFormModal.value = true
  }

  function openDelete(row: Record<string, unknown>) {
    deleteTarget.value = row
    showDeleteModal.value = true
  }

  function draftText(key: string): string | null {
    const v = formDraft.value[key]
    if (v == null || v === '') return null
    return String(v)
  }

  function draftNumber(key: string): number | null {
    const v = formDraft.value[key]
    return typeof v === 'number' && !Number.isNaN(v) ? v : null
  }

  function draftUser(key: string): string | null {
    const v = formDraft.value[key]
    return typeof v === 'string' && v ? v : null
  }

  /** year picker: Naive formatted-value 为 yyyy 字符串，草稿存 number */
  function draftYearFormatted(key: string): string | null {
    const n = draftNumber(key)
    return n == null ? null : String(n)
  }

  function setDraft(key: string, value: unknown) {
    formDraft.value[key] = value ?? null
  }

  function setYearDraft(key: string, formatted: string | null) {
    if (!formatted) {
      formDraft.value[key] = null
      return
    }
    const n = Number(formatted)
    formDraft.value[key] = Number.isFinite(n) ? n : null
  }

  function selectOptionsFor(f: FieldDef): SelectOption[] {
    const base = f.options ? [...f.options] : []
    const current = formDraft.value[f.key]
    if (current != null && current !== '') {
      const val = String(current)
      if (!base.some(o => String(o.value) === val)) {
        base.unshift({ label: val, value: val })
      }
    }
    return base
  }

  async function submitForm() {
    const body = buildBody(formDraft.value)
    for (const f of currentTable.value.fields) {
      if (!f.required) continue
      const v = body[f.key]
      if (v == null || v === '') {
        message.warning(`请填写${f.label}`)
        return
      }
    }
    saving.value = true
    try {
      if (formMode.value === 'create') {
        await createBizRow(domain.value, currentTable.value.key, body)
        message.success('已创建')
      } else if (editKey.value) {
        const table = currentTable.value.key
        if (compositeKeyChanged(body)) {
          await createBizRow(domain.value, table, body)
          const query: Record<string, string> = {
            tenantId: String(editKey.value.tenantId ?? BIZ_TENANT_ID),
          }
          await deleteBizRow(domain.value, table, editKey.value, query)
        } else {
          await updateBizRow(domain.value, table, editKey.value, body)
        }
        message.success('已保存')
      }
      showFormModal.value = false
      await loadRows()
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '保存失败')
      console.error(e)
    } finally {
      saving.value = false
    }
  }

  async function confirmDelete() {
    if (!deleteTarget.value) return
    deleting.value = true
    try {
      const query: Record<string, string> = {}
      const table = currentTable.value.key
      if (table === 'leave-balances' || table === 'attendance-months') {
        query.tenantId = String(deleteTarget.value.tenantId ?? BIZ_TENANT_ID)
      }
      await deleteBizRow(domain.value, table, deleteTarget.value, query)
      message.success('已删除')
      showDeleteModal.value = false
      deleteTarget.value = null
      await loadRows()
    } catch (e) {
      message.error(e instanceof ApiError ? e.message : '删除失败')
      console.error(e)
    } finally {
      deleting.value = false
    }
  }

  watch(domain, () => {
    const next = BIZ_TABLE_DEFS[domain.value][0].key
    if (tableKey.value === next) {
      void loadRows()
    } else {
      tableKey.value = next
    }
  })

  watch(tableKey, () => {
    void loadRows()
  })

  onMounted(() => {
    void refresh()
  })

  return {
    domains: BIZ_DOMAINS,
    loading,
    saving,
    deleting,
    domain,
    tableKey,
    rows,
    showFormModal,
    formMode,
    formDraft,
    showDeleteModal,
    tableDefs,
    currentTable,
    userOptions,
    columns,
    rowKeyOf,
    refresh,
    selectTable,
    openCreate,
    draftText,
    draftNumber,
    draftUser,
    draftYearFormatted,
    setDraft,
    setYearDraft,
    selectOptionsFor,
    submitForm,
    confirmDelete,
  }
}
