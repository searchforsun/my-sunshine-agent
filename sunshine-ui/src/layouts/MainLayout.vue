<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, NDropdown, NIcon, NInput, useDialog, NButton, useMessage, type MenuOption, type DropdownOption } from 'naive-ui'
import { BookOutline, StatsChartOutline, SettingsOutline, LogOutOutline, EllipsisHorizontal, SparklesOutline, HardwareChipOutline, ConstructOutline, GitNetworkOutline, ChevronDownOutline, CreateOutline, TrashOutline, DocumentTextOutline, BriefcaseOutline, AlbumsOutline, AddOutline, ChatbubblesOutline } from '@vicons/ionicons5'
import { h, type Component, computed, onMounted, ref, watch } from 'vue'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import { useConversationAttention } from '../composables/useConversationAttention'
import { friendlyErrorMessage } from '../api/apiError'
import { listWorkspaces, createWorkspace } from '../api/workspaces'
import type { WorkspaceVO } from '../api/workspaces'
import BrandMark from '../components/BrandMark.vue'
import SidebarToggle from '../components/SidebarToggle.vue'
import UserSettingsModal from '../components/UserSettingsModal.vue'
import ConversationSidebarList from '../components/ConversationSidebarList.vue'

type TabKey = 'platform' | 'chat' | 'workspace'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const chatStore = useChatStore()
const message = useMessage()
const {
  attentionByConv,
  getAttention,
  clearAttention,
  requestScrollToBottom,
} = useConversationAttention()

const TAB_STORAGE_KEY = 'sunshine-sidebar-tab'
const expandedTab = ref<TabKey | null>((localStorage.getItem(TAB_STORAGE_KEY) as TabKey) || 'chat')

function setExpandedTab(key: TabKey) {
  if (expandedTab.value === key) {
    expandedTab.value = null
    localStorage.removeItem(TAB_STORAGE_KEY)
  } else {
    expandedTab.value = key
    localStorage.setItem(TAB_STORAGE_KEY, key)
  }
}

const tabs: Array<{ key: TabKey; label: string; icon: Component }> = [
  { key: 'platform', label: '平台', icon: SparklesOutline },
  { key: 'chat', label: '对话', icon: ChatbubblesOutline },
  { key: 'workspace', label: '工作区', icon: ConstructOutline },
]

const platformMenuOptions: MenuOption[] = [
  { label: '知识库', key: 'knowledge', icon: renderIcon(BookOutline) },
  { label: 'Skills', key: 'skills', icon: renderIcon(SparklesOutline) },
  { label: '工作流', key: 'workflows', icon: renderIcon(GitNetworkOutline) },
  { label: '工具', key: 'tools', icon: renderIcon(ConstructOutline) },
  { label: '智能体', key: 'agents', icon: renderIcon(HardwareChipOutline) },
  { label: '上下文', key: 'context', icon: renderIcon(AlbumsOutline) },
  { label: '提示词', key: 'prompts', icon: renderIcon(DocumentTextOutline) },
  { label: '业务数据', key: 'biz-data', icon: renderIcon(BriefcaseOutline) },
  { label: '系统状态', key: 'status', icon: renderIcon(StatsChartOutline) },
]

const FILL_CONTENT_ROUTES = new Set(['chat', 'knowledge', 'skills', 'workflows', 'tools', 'agents', 'context', 'prompts', 'biz-data', 'workflow-diff', 'skill-diff'])
const contentFill = computed(() => FILL_CONTENT_ROUTES.has(String(route.name ?? '')))
const hideSidebarFab = computed(() => contentFill.value || route.name === 'skill-diff')

function renderIcon(icon: Component) {
  return () => h(icon)
}

function renderDropdownIcon(icon: Component) {
  return () => h(NIcon, { size: 16 }, { default: () => h(icon) })
}

function handleMenuClick(key: string) {
  void router.push({ name: key })
}

const activeKey = computed(() => {
  if (route.name === 'skill-diff') return 'skills'
  if (route.name === 'workflow-diff') return 'workflows'
  if (route.name === 'chat') return ''
  return (route.name as string) || ''
})

const { theme, toggle: toggleTheme } = useTheme()
const { sidebarVisible } = useSidebar()
const isDark = computed(() => theme.value === 'dark')
const dialog = useDialog()

const displayNickname = computed(() => authStore.user?.nickname || '用户')
const userInitial = computed(() => displayNickname.value.charAt(0).toUpperCase())
const showSettings = ref(false)

const userMenuOptions: DropdownOption[] = [
  { label: '设置', key: 'settings', icon: renderDropdownIcon(SettingsOutline) },
  { type: 'divider', key: 'd1' },
  { label: '退出登录', key: 'logout', icon: renderDropdownIcon(LogOutOutline) },
]

function handleUserMenu(key: string) {
  if (key === 'settings') {
    showSettings.value = true
    return
  }
  if (key === 'logout') {
    handleLogout()
  }
}

function handleLogout() {
  void (async () => {
    await authStore.logout()
    await router.replace('/login')
  })()
}

function handleNewChat() {
  void (async () => {
    try {
      await chatStore.create()
      if (route.name !== 'chat') router.push('/chat')
    } catch (e) {
      console.error('[MainLayout] 创建会话失败', e)
    }
  })()
}

function handleSwitchConversation(id: string) {
  void (async () => {
    if (getAttention(id)) {
      requestScrollToBottom(id)
      clearAttention(id)
    }
    await chatStore.switchTo(id)
    if (route.name !== 'chat') router.push('/chat')
  })()
}

function conversationMenuOptions(id: string): DropdownOption[] {
  return [
    { label: '重命名', key: `rename:${id}`, icon: renderDropdownIcon(CreateOutline) },
    { type: 'divider', key: `div:${id}` },
    {
      label: '删除',
      key: `delete:${id}`,
      props: { class: 'history-dropdown-delete' },
      icon: renderDropdownIcon(TrashOutline),
    },
  ]
}

function handleConversationMenu(key: string) {
  const sep = key.indexOf(':')
  if (sep < 0) return
  const action = key.slice(0, sep)
  const id = key.slice(sep + 1)
  if (action === 'rename') handleRenameConversation(id)
  else if (action === 'delete') handleDeleteConversation(id)
}

function handleRenameConversation(id: string) {
  const conv = chatStore.conversations.find(c => c.id === id)
  const inputValue = ref(conv?.title ?? '')
  dialog.create({
    class: 'sunshine-dialog',
    showIcon: false,
    title: '重命名对话',
    content: () => h(NInput, {
      value: inputValue.value,
      maxlength: 64,
      placeholder: '输入对话标题',
      autofocus: true,
      onUpdateValue: (v: string) => { inputValue.value = v },
    }),
    positiveText: '保存',
    negativeText: '取消',
    onPositiveClick: () => submitRename(id, inputValue.value.trim()),
  })
}

async function submitRename(id: string, title: string): Promise<boolean> {
  if (!title) return false
  try {
    await chatStore.rename(id, title)
    return true
  } catch (e) {
    console.error('[MainLayout] 重命名失败', e)
    return false
  }
}

function handleDeleteConversation(id: string) {
  const conv = chatStore.conversations.find(c => c.id === id)
  const title = conv?.title || '该对话'
  dialog.create({
    class: 'sunshine-dialog',
    showIcon: false,
    title: '永久删除对话',
    content: `确定删除「${title}」吗？\n此操作不可撤销，对话内容将永久删除且无法恢复。`,
    positiveText: '永久删除',
    negativeText: '取消',
    positiveButtonProps: { type: 'error', size: 'medium' },
    negativeButtonProps: { ghost: false, quaternary: true, size: 'medium' },
    onPositiveClick: () => {
      void chatStore.remove(id)
    },
  })
}

// ===== 工作区 =====
const workspaces = ref<WorkspaceVO[]>([])
const loadingWorkspaces = ref(false)
const showCreateWorkspace = ref(false)
const newWsName = ref('')
const newWsRepoUrl = ref('')
const newWsBranch = ref('main')
const creatingWs = ref(false)

async function fetchWorkspaces() {
  loadingWorkspaces.value = true
  try { workspaces.value = await listWorkspaces() }
  catch { /* silently fail */ }
  finally { loadingWorkspaces.value = false }
}

watch(expandedTab, (tab) => {
  if (tab === 'workspace' && workspaces.value.length === 0) {
    fetchWorkspaces()
  }
})

async function handleCreateWorkspace() {
  const name = newWsName.value.trim()
  const url = newWsRepoUrl.value.trim()
  if (!name) { message.warning('请输入名称'); return }
  if (!url) { message.warning('请输入仓库地址'); return }
  creatingWs.value = true
  try {
    await createWorkspace({ name, repoUrl: url, repoBranch: newWsBranch.value || 'main' })
    message.success('工作区已创建')
    showCreateWorkspace.value = false
    newWsName.value = ''
    newWsRepoUrl.value = ''
    newWsBranch.value = 'main'
    await fetchWorkspaces()
  } catch (e) {
    message.error(friendlyErrorMessage(e, '创建失败'))
  } finally { creatingWs.value = false }
}

function handleWorkspaceClick(ws: WorkspaceVO) {
  void (async () => {
    try {
      await chatStore.create({ kind: 'task', workspaceId: ws.id, checkoutPath: '/workspace/main' })
      if (route.name !== 'chat') router.push('/chat')
    } catch (e) {
      console.error('[MainLayout] 创建任务会话失败', e)
    }
  })()
}

onMounted(() => {
  void chatStore.init()
})
</script>

<template>
  <NLayout has-sider class="app-shell">
    <NLayoutSider
      v-if="sidebarVisible"
      bordered
      :width="280"
      class="sidebar"
    >
      <div class="brand">
        <BrandMark class="brand-mark" />
        <span class="brand-name">Sunshine<span class="brand-ai"> AI</span></span>
      </div>

      <!-- 三 Tab 切换 -->
      <div class="tab-bar">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          class="tab-btn"
          :class="{ active: expandedTab === tab.key }"
          @click="setExpandedTab(tab.key)"
        >
          <NIcon :size="16" :component="tab.icon" />
          <span>{{ tab.label }}</span>
          <NIcon :size="12" :component="ChevronDownOutline" class="tab-chevron" :class="{ rotated: expandedTab === tab.key }" />
        </button>
      </div>

      <!-- 平台面板 -->
      <div v-show="expandedTab === 'platform'" class="tab-panel">
        <NMenu
          :value="activeKey"
          :options="platformMenuOptions"
          @update:value="handleMenuClick"
          class="nav-menu"
        />
      </div>

      <!-- 对话面板 -->
      <div v-show="expandedTab === 'chat'" class="tab-panel chat-panel">
        <button type="button" class="chat-new-btn" @click="handleNewChat">
          <NIcon :size="16" :component="ChatbubblesOutline" />
          <span>新对话</span>
        </button>
        <div class="chat-history">
          <ConversationSidebarList
            :menu-options="conversationMenuOptions"
            @switch="handleSwitchConversation"
            @menu="handleConversationMenu"
          />
        </div>
      </div>

      <!-- 工作区面板 -->
      <div v-show="expandedTab === 'workspace'" class="tab-panel workspace-panel">
        <div class="ws-panel-header">
          <span class="ws-panel-title">工作区</span>
          <button type="button" class="ws-add-btn" title="添加工作区" @click="showCreateWorkspace = true">
            <NIcon :size="16" :component="AddOutline" />
          </button>
        </div>
        <div class="ws-scroll">
          <div v-if="loadingWorkspaces" class="ws-loading">加载中...</div>
          <div v-else-if="workspaces.length === 0" class="ws-empty">
            <span class="ws-empty-text">暂无工作区</span>
            <NButton size="tiny" quaternary @click="showCreateWorkspace = true">创建</NButton>
          </div>
          <div v-else class="ws-list">
            <button
              v-for="ws in workspaces"
              :key="ws.id"
              type="button"
              class="ws-item"
              @click="handleWorkspaceClick(ws)"
            >
              <div class="ws-item-name">{{ ws.name }}</div>
              <div class="ws-item-repo">{{ ws.repoUrl }}</div>
            </button>
          </div>
        </div>
      </div>

      <!-- 用户区 -->
      <div class="sidebar-user">
        <div class="user-avatar" aria-hidden="true">{{ userInitial }}</div>
        <span class="user-nickname" :title="displayNickname">{{ displayNickname }}</span>
        <div class="sidebar-user-actions">
          <button class="theme-toggle" type="button" @click="toggleTheme" :title="isDark ? '切换浅色模式' : '切换深色模式'">
            <svg v-if="isDark" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <circle cx="12" cy="12" r="5" />
              <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
            </svg>
            <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
          </button>
          <NDropdown trigger="click" size="small" :options="userMenuOptions" @select="handleUserMenu">
            <button type="button" class="user-more-btn" title="更多" aria-label="更多">
              <EllipsisHorizontal width="18" height="18" />
            </button>
          </NDropdown>
        </div>
      </div>
    </NLayoutSider>

    <UserSettingsModal v-model:show="showSettings" />

    <!-- 创建工作区弹窗 -->
    <NModal
      :show="showCreateWorkspace"
      preset="card"
      title="新建工作区"
      style="width:480px"
      @update:show="showCreateWorkspace = $event"
    >
      <div class="ws-create-form">
        <label class="ws-create-label">名称</label>
        <NInput v-model:value="newWsName" class="sun-field" placeholder="my-project" maxlength="128" :disabled="creatingWs" />
        <label class="ws-create-label">仓库地址</label>
        <NInput v-model:value="newWsRepoUrl" class="sun-field" placeholder="https://github.com/user/repo" maxlength="512" :disabled="creatingWs" />
        <label class="ws-create-label">默认分支</label>
        <NInput v-model:value="newWsBranch" class="sun-field" maxlength="128" :disabled="creatingWs" />
      </div>
      <template #footer>
        <NButton quaternary :disabled="creatingWs" @click="showCreateWorkspace = false">取消</NButton>
        <NButton type="primary" :loading="creatingWs" @click="handleCreateWorkspace">创建</NButton>
      </template>
    </NModal>

    <NLayoutContent class="content-area" :class="{ 'content-area--fill': contentFill }">
      <SidebarToggle v-if="!sidebarVisible && !hideSidebarFab" variant="fab" />
      <router-view />
    </NLayoutContent>
  </NLayout>
</template>

<style scoped>
.sidebar-user-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  margin-left: auto;
}

/* --- Sidebar --- */
.app-shell {
  height: 100vh;
  min-height: 100vh;
}

.app-shell :deep(.n-layout-scroll-container) {
  height: 100%;
}

.sidebar {
  background: var(--sun-sidebar-bg) !important;
  border-right: 1px solid var(--sun-border) !important;
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.sidebar :deep(.n-layout-sider-border) {
  background-color: var(--sun-border) !important;
}

.sidebar :deep(.n-layout-sider-scroll-container) {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  height: 100%;
}

/* --- Brand --- */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px 14px;
  border-bottom: 1px solid var(--sun-border);
}

.brand-mark {
  flex-shrink: 0;
  display: block;
}

.brand-name {
  font-size: var(--sun-font-lg);
  font-weight: 600;
  letter-spacing: -0.45px;
  color: var(--sun-text);
  line-height: 1;
  white-space: nowrap;
}

.brand-ai {
  font-weight: 400;
  color: var(--sun-text-muted);
}

/* --- Tab Bar --- */
.tab-bar {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 6px 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-xs, 12px);
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.tab-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text-secondary);
}

.tab-btn.active {
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

.tab-chevron {
  opacity: 0.5;
  transition: transform 0.18s ease;
  flex-shrink: 0;
}

.tab-chevron.rotated {
  transform: rotate(180deg);
  opacity: 0.8;
}

/* --- Tab Panel --- */
.tab-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* --- Platform --- */
.nav-menu {
  flex-shrink: 0;
  padding: 4px 8px;
}

.nav-menu :deep(.n-menu) {
  --n-color: transparent !important;
  --n-item-color-hover: var(--sun-row-hover) !important;
  --n-item-color-active: var(--sun-row-hover) !important;
  --n-item-color-active-hover: var(--sun-row-hover) !important;
}

.nav-menu :deep(.n-menu-item-content) {
  border-radius: var(--radius-sm);
  transition: background 0.15s, color 0.15s, box-shadow 0.15s;
}

.nav-menu :deep(.n-menu-item-content--selected) {
  font-weight: 600;
}

.nav-menu :deep(.n-menu-item-content--selected .n-menu-item-content-header),
.nav-menu :deep(.n-menu-item-content--selected .n-menu-item-content__icon) {
  color: var(--sun-text) !important;
}

.nav-menu :deep(.n-menu-item-content-header) {
  font-size: var(--sun-font-base);
  color: var(--sun-text-secondary);
}

.nav-menu :deep(.n-menu-item-content__icon) {
  color: var(--sun-text-secondary);
}

.nav-menu :deep(.n-menu-item) {
  height: 40px;
}

/* --- Chat Panel --- */
.chat-panel {
  padding: 0;
}

.chat-new-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: calc(100% - 16px);
  margin: 8px 8px 4px;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: transparent;
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-sm);
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
  flex-shrink: 0;
}

.chat-new-btn:hover {
  border-color: var(--sun-accent);
  color: var(--sun-text);
}

.chat-history {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 8px 8px;
  overflow: hidden;
}

/* --- Workspace Panel --- */
.workspace-panel {
  padding: 0;
}

.ws-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 6px;
  flex-shrink: 0;
}

.ws-panel-title {
  font-size: var(--sun-font-xs, 12px);
  font-weight: 600;
  color: var(--sun-text-muted);
  letter-spacing: 0.02em;
}

.ws-add-btn {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.ws-add-btn:hover {
  border-color: var(--sun-accent);
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

.ws-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 8px;
}

.ws-loading {
  padding: 16px;
  color: var(--sun-text-muted);
  text-align: center;
  font-size: var(--sun-font-xs);
}

.ws-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 20px 8px 4px;
}

.ws-empty-text {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.ws-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ws-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}

.ws-item:hover {
  border-color: var(--sun-accent);
  background: var(--sun-row-hover);
}

.ws-item-name {
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
}

.ws-item-repo {
  font-size: var(--sun-font-xs, 12px);
  color: var(--sun-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* --- 创建工作区弹窗 --- */
.ws-create-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ws-create-label {
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
}

/* --- 用户区 --- */
.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 14px 14px 16px;
  flex-shrink: 0;
  border-top: 1px solid var(--sun-border);
}

.user-nickname {
  flex: 1;
  min-width: 0;
  font-size: var(--sun-font-base);
  font-weight: 600;
  color: var(--sun-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-more-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}

.user-more-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.user-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  color: var(--sun-text-secondary);
  font-size: var(--sun-font-base);
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.theme-toggle {
  width: 28px; height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, background 0.15s, color 0.15s, box-shadow 0.15s;
  flex-shrink: 0;
}

.theme-toggle:hover {
  color: var(--sun-text);
  background: var(--sun-row-hover);
}

/* --- Content --- */
.content-area {
  background: var(--sun-black);
  overflow: auto;
  height: 100%;
  min-height: 0;
}

.content-area--fill {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.content-area--fill :deep(> *) {
  flex: 1;
  min-height: 0;
}
</style>
