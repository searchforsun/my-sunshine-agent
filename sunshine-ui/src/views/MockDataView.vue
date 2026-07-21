<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  NButton,
  NEmpty,
  NIcon,
  NSpace,
  NSpin,
  NTabPane,
  NTabs,
  useMessage,
} from 'naive-ui'
import { RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import {
  fetchMockSnapshot,
  listMockUsers,
  resetMockData,
  type MockDomain,
  type MockSnapshot,
} from '../api/mockData'
import { ApiError } from '../api/apiError'

const DOMAINS: { key: MockDomain; label: string }[] = [
  { key: 'finance', label: '财务' },
  { key: 'hr', label: '人事' },
  { key: 'oa', label: 'OA' },
]

const message = useMessage()
const loading = ref(false)
const resetting = ref(false)
const domain = ref<MockDomain>('finance')
const users = ref<string[]>([])
const selectedUserId = ref<string | null>(null)
const snapshot = ref<MockSnapshot | null>(null)
const tenantId = 'default'

const domainLabel = computed(
  () => DOMAINS.find(d => d.key === domain.value)?.label ?? domain.value,
)

const snapshotJson = computed(() =>
  snapshot.value ? JSON.stringify(snapshot.value, null, 2) : '',
)

const tableRows = computed(() => {
  const data = snapshot.value
  const empty: Array<{ section: string; rows: Array<Record<string, unknown>> }> = []
  if (!data) return empty
  if (domain.value === 'finance' && 'expenses' in data) {
    return [
      { section: '报销单', rows: data.expenses as Array<Record<string, unknown>> },
      { section: '财务待办', rows: data.inbox as Array<Record<string, unknown>> },
    ]
  }
  if (domain.value === 'oa' && 'tasks' in data) {
    return [{ section: '待办任务', rows: data.tasks as Array<Record<string, unknown>> }]
  }
  if (domain.value === 'hr' && 'leaveRequests' in data) {
    const balance = data.leaveBalance
      ? [data.leaveBalance as Record<string, unknown>]
      : ([] as Array<Record<string, unknown>>)
    const attendance: Array<Record<string, unknown>> = Object.entries(data.attendance ?? {}).map(
      ([ym, v]) => ({
        yearMonth: ym,
        ...(typeof v === 'object' && v ? (v as Record<string, unknown>) : { value: v }),
      }),
    )
    return [
      { section: '假期余额', rows: balance },
      { section: '请假单', rows: data.leaveRequests as Array<Record<string, unknown>> },
      { section: '考勤月报', rows: attendance },
    ]
  }
  return empty
})

async function loadUsers() {
  loading.value = true
  try {
    const list = await listMockUsers(domain.value, tenantId)
    users.value = list
    if (selectedUserId.value && !list.includes(selectedUserId.value)) {
      selectedUserId.value = null
      snapshot.value = null
    }
    if (!selectedUserId.value && list.length > 0) {
      selectedUserId.value = list[0]
      return
    }
    if (selectedUserId.value) {
      await loadSnapshot()
    }
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '加载用户列表失败')
    console.error(e)
  } finally {
    loading.value = false
  }
}

async function loadSnapshot() {
  if (!selectedUserId.value) {
    snapshot.value = null
    return
  }
  loading.value = true
  try {
    snapshot.value = await fetchMockSnapshot(domain.value, selectedUserId.value, tenantId)
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '加载快照失败')
    console.error(e)
    snapshot.value = null
  } finally {
    loading.value = false
  }
}

async function onReset() {
  resetting.value = true
  try {
    await resetMockData(domain.value, tenantId)
    message.success(`${domainLabel.value} 种子已重置`)
    await loadUsers()
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '重置失败')
    console.error(e)
  } finally {
    resetting.value = false
  }
}

function selectUser(userId: string) {
  if (selectedUserId.value === userId) return
  selectedUserId.value = userId
}

watch(domain, () => {
  selectedUserId.value = null
  snapshot.value = null
  void loadUsers()
})

watch(selectedUserId, (id) => {
  if (!id) {
    snapshot.value = null
    return
  }
  void loadSnapshot()
})

onMounted(() => {
  void loadUsers()
})

function formatCell(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
</script>

<template>
  <div class="mock-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>业务数据</h2>
      </div>
      <NSpace :size="8">
        <NButton round secondary :loading="loading" @click="loadUsers">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="resetting" @click="onReset">
          重置种子
        </NButton>
      </NSpace>
    </header>

    <div class="domain-tabs">
      <NTabs v-model:value="domain" type="line" size="small" animated>
        <NTabPane v-for="d in DOMAINS" :key="d.key" :name="d.key" :tab="d.label" />
      </NTabs>
    </div>

    <div class="mock-layout">
      <aside class="list-panel">
        <div class="panel-head">
          <span class="panel-title">用户</span>
          <span class="panel-count">{{ users.length }}</span>
        </div>
        <NSpin :show="loading" size="small" class="list-spin">
          <div class="list-body">
            <div v-if="users.length === 0 && !loading" class="empty-wrap">
              <NEmpty size="small" description="暂无用户" />
            </div>
            <button
              v-for="uid in users"
              :key="uid"
              type="button"
              class="user-card"
              :class="{ active: uid === selectedUserId }"
              @click="selectUser(uid)"
            >
              <span class="user-id">{{ uid }}</span>
            </button>
          </div>
        </NSpin>
      </aside>

      <section class="detail-panel">
        <div v-if="!selectedUserId" class="detail-empty">
          <NEmpty description="选择左侧用户查看数据" />
        </div>
        <template v-else>
          <div class="panel-head detail-head">
            <span class="panel-title">{{ selectedUserId }} · {{ domainLabel }}</span>
          </div>
          <NSpin :show="loading" size="small" class="detail-spin">
            <div class="detail-body">
              <div v-for="block in tableRows" :key="block.section" class="section-block">
                <h3 class="section-title">{{ block.section }}</h3>
                <div v-if="block.rows.length === 0" class="section-empty">（空）</div>
                <div v-else class="table-wrap">
                  <table class="data-table">
                    <thead>
                      <tr>
                        <th v-for="col in Object.keys(block.rows[0])" :key="col">{{ col }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(row, idx) in block.rows" :key="idx">
                        <td v-for="col in Object.keys(block.rows[0])" :key="col">
                          {{ formatCell(row[col]) }}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
              <div class="json-block">
                <h3 class="section-title">JSON</h3>
                <pre class="json-pre">{{ snapshotJson }}</pre>
              </div>
            </div>
          </NSpin>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.mock-root {
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
  gap: 16px;
  flex-shrink: 0;
  min-height: 36px;
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
  letter-spacing: -0.4px;
  line-height: 36px;
  color: var(--sun-text);
}

.domain-tabs {
  flex-shrink: 0;
  border-bottom: 1px solid var(--sun-border);
}

.domain-tabs :deep(.n-tabs-nav) {
  --n-tab-text-color: var(--sun-text-muted);
  --n-tab-text-color-active: var(--sun-text);
  --n-tab-text-color-hover: var(--sun-text);
  --n-bar-color: var(--sun-text);
  --n-pane-padding-top: 0;
}

.mock-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(240px, 280px) 1fr;
  gap: 16px;
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
  gap: 8px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-count {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.list-spin,
.detail-spin {
  flex: 1;
  min-height: 0;
}

.list-spin :deep(.n-spin-content),
.detail-spin :deep(.n-spin-content) {
  height: 100%;
}

.list-body {
  padding: 12px 14px 14px;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty-wrap {
  padding: 24px 0;
}

.user-card {
  display: block;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.user-card:hover {
  border-color: var(--sun-border-light);
}

.user-card.active {
  box-shadow: inset 0 0 0 1px var(--sun-text);
  border-color: var(--sun-text);
  font-weight: 600;
}

.user-id {
  font-size: 13px;
  font-family: var(--sun-font-mono, ui-monospace, monospace);
}

.detail-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-body {
  padding: 16px;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.section-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.section-empty {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.table-wrap {
  overflow-x: auto;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.data-table th,
.data-table td {
  padding: 8px 10px;
  border-bottom: 1px solid var(--sun-border);
  text-align: left;
  vertical-align: top;
  color: var(--sun-text);
  white-space: nowrap;
}

.data-table th {
  font-weight: 600;
  color: var(--sun-text-muted);
  background: transparent;
}

.data-table tr:last-child td {
  border-bottom: none;
}

.json-pre {
  margin: 0;
  padding: 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text);
  font-size: 12px;
  line-height: 1.5;
  overflow: auto;
  max-height: 360px;
  white-space: pre-wrap;
  word-break: break-word;
}

.action-btn {
  min-width: 96px;
}
</style>
