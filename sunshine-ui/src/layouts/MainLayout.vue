<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, NDropdown, NIcon, NInput, useDialog, type MenuOption, type DropdownOption } from 'naive-ui'
import { ChatbubblesOutline, BookOutline, StatsChartOutline, SettingsOutline, LogOutOutline, EllipsisHorizontal, LayersOutline, PeopleOutline, ConstructOutline, GitNetworkOutline, ChevronDownOutline, CreateOutline, TrashOutline, DocumentTextOutline, BriefcaseOutline } from '@vicons/ionicons5'
import { h, type Component, computed, onMounted, ref, watch } from 'vue'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import { useConversationAttention } from '../composables/useConversationAttention'
import { useConversationSidebarIndicator } from '../composables/useConversationSidebarIndicator'
import BrandMark from '../components/BrandMark.vue'
import SidebarToggle from '../components/SidebarToggle.vue'
import UserSettingsModal from '../components/UserSettingsModal.vue'
import ConversationSidebarList from '../components/ConversationSidebarList.vue'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const chatStore = useChatStore()
const {
  attentionByConv,
  getAttention,
  clearAttention,
  requestScrollToBottom,
} = useConversationAttention()
const {
  navMenuIndicator,
} = useConversationSidebarIndicator()

const PLATFORM_NAV_STORAGE_KEY = 'sunshine-sidebar-platform-nav'
/** 「新对话」以下的平台入口：默认展开；状态记入 localStorage */
const platformNavOpen = ref(localStorage.getItem(PLATFORM_NAV_STORAGE_KEY) !== '0')

function togglePlatformNav() {
  platformNavOpen.value = !platformNavOpen.value
  localStorage.setItem(PLATFORM_NAV_STORAGE_KEY, platformNavOpen.value ? '1' : '0')
}

function setPlatformNavOpen(open: boolean) {
  if (platformNavOpen.value === open) return
  platformNavOpen.value = open
  localStorage.setItem(PLATFORM_NAV_STORAGE_KEY, open ? '1' : '0')
}

const chatMenuOptions = computed((): MenuOption[] => {
  void attentionByConv.size
  void chatStore.conversations.map(c => c.messages?.length ?? 0)
  const navInd = navMenuIndicator(chatStore.conversations)
  return [
    {
      label: () => h('span', { class: 'nav-menu-label' }, [
        '新对话',
        navInd === 'streaming'
          ? h('span', { class: 'nav-streaming-dot', 'aria-hidden': 'true', title: '有对话正在生成' })
          : navInd
            ? h('span', {
              class: ['nav-attention-dot', `is-${navInd}`],
              'aria-hidden': 'true',
              title: navInd === 'hitl_pending' ? '有待确认项' : '回答已完成',
            })
            : null,
      ]),
      key: 'chat',
      icon: renderIcon(ChatbubblesOutline),
    },
  ]
})

const platformMenuOptions: MenuOption[] = [
  { label: '知识库', key: 'knowledge', icon: renderIcon(BookOutline) },
  { label: 'Skills', key: 'skills', icon: renderIcon(LayersOutline) },
  { label: '工作流', key: 'workflows', icon: renderIcon(GitNetworkOutline) },
  { label: '工具', key: 'tools', icon: renderIcon(ConstructOutline) },
  { label: '专家', key: 'experts', icon: renderIcon(PeopleOutline) },
  { label: '提示词', key: 'prompts', icon: renderIcon(DocumentTextOutline) },
  { label: '业务数据', key: 'mock-data', icon: renderIcon(BriefcaseOutline) },
  { label: '系统状态', key: 'status', icon: renderIcon(StatsChartOutline) },
]

const FILL_CONTENT_ROUTES = new Set(['chat', 'knowledge', 'skills', 'workflows', 'tools', 'experts', 'prompts', 'mock-data', 'workflow-diff', 'skill-diff'])
const contentFill = computed(() => FILL_CONTENT_ROUTES.has(String(route.name ?? '')))
const hideSidebarFab = computed(() =>
  contentFill.value || route.name === 'skill-diff',
)

function renderIcon(icon: Component) {
  return () => h(icon)
}

function renderDropdownIcon(icon: Component) {
  return () => h(NIcon, { size: 16 }, { default: () => h(icon) })
}

function handleMenuClick(key: string) {
  if (key === 'chat') {
    handleNewChat()
    return
  }
  router.push(`/${key}`)
}

const activeKey = computed(() => {
  if (route.name === 'skill-diff') return 'skills'
  if (route.name === 'workflow-diff') return 'workflows'
  // 「新对话」是动作入口，不高亮；仅平台页高亮对应项
  if (route.name === 'chat') return ''
  return (route.name as string) || ''
})

/** 进入平台页时自动展开，避免当前路由藏在折叠区内 */
watch(activeKey, (key) => {
  if (key && key !== 'chat') {
    setPlatformNavOpen(true)
  }
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
  setPlatformNavOpen(false)
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
  setPlatformNavOpen(false)
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

onMounted(() => {
  void chatStore.init()
})
</script>

<template>
  <NLayout has-sider class="app-shell">
    <!-- Sidebar -->
    <NLayoutSider
      v-if="sidebarVisible"
      bordered
      :width="280"
      class="sidebar"
    >
      <!-- Brand -->
      <div class="brand">
        <BrandMark class="brand-mark" />
        <span class="brand-name">Sunshine<span class="brand-ai"> AI</span></span>
      </div>

      <!-- Nav：新对话固定；其下平台入口可折叠；历史列表常驻 -->
      <div class="nav-block">
        <NMenu
          :value="activeKey"
          :options="chatMenuOptions"
          @update:value="handleMenuClick"
          class="nav-menu nav-menu--chat"
        />
        <div class="nav-platform">
          <button
            type="button"
            class="nav-collapse-toggle"
            :aria-expanded="platformNavOpen"
            :title="platformNavOpen ? '折叠平台入口' : '展开平台入口'"
            @click="togglePlatformNav"
          >
            <NIcon
              :component="ChevronDownOutline"
              :size="14"
              class="nav-collapse-chevron"
              :class="{ 'is-collapsed': !platformNavOpen }"
            />
            <span class="nav-collapse-label">平台</span>
            <span class="nav-collapse-hint">{{ platformNavOpen ? '收起' : '展开' }}</span>
          </button>
          <Transition name="nav-platform">
            <div v-show="platformNavOpen" class="nav-platform-body">
              <NMenu
                :value="activeKey"
                :options="platformMenuOptions"
                @update:value="handleMenuClick"
                class="nav-menu nav-menu--platform"
              />
            </div>
          </Transition>
        </div>
      </div>

      <div class="chat-history">
        <ConversationSidebarList
          :menu-options="conversationMenuOptions"
          @switch="handleSwitchConversation"
          @menu="handleConversationMenu"
        />
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

    <!-- Content -->
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

/* 与自定义边框统一，避免 Naive bordered 延迟换色 */
.sidebar :deep(.n-layout-sider-border) {
  background-color: var(--sun-border) !important;
}
/* Naive UI 内部滚动容器也需要 flex 列布局，否则 margin-top:auto 不生效 */
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

/* --- Nav --- */
.nav-block {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 6px;
  padding-bottom: 4px;
}

.nav-menu {
  flex-shrink: 0;
  padding: 0 8px;
}

.nav-menu--chat :deep(.n-menu-item) {
  height: 40px;
}

.nav-platform {
  margin: 4px 0 0;
  padding: 6px 8px 0;
  border-top: 1px solid var(--sun-border);
}

.nav-collapse-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 7px 12px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.nav-collapse-toggle:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text-secondary);
}

.nav-collapse-toggle:hover .nav-collapse-hint {
  opacity: 1;
}

.nav-collapse-label {
  flex: 1;
  text-align: left;
  letter-spacing: 0.02em;
}

.nav-collapse-hint {
  font-size: 11px;
  font-weight: 400;
  color: var(--sun-text-muted);
  opacity: 0;
  transition: opacity 0.15s;
}

.nav-collapse-chevron {
  transition: transform 0.18s ease;
  flex-shrink: 0;
  opacity: 0.85;
}

.nav-collapse-chevron.is-collapsed {
  transform: rotate(-90deg);
}

.nav-platform-body {
  overflow: hidden;
}

.nav-menu--platform {
  margin-top: 2px;
  padding: 0;
}

.nav-menu--platform :deep(.n-menu-item) {
  height: 40px;
}

.nav-menu--platform :deep(.n-menu-item-content-header) {
  font-size: var(--sun-font-base);
}

.nav-platform-enter-active,
.nav-platform-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.nav-platform-enter-from,
.nav-platform-leave-to {
  opacity: 0;
  transform: translateY(-6px);
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

/* --- 用户区 --- */
.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 14px 14px 16px;
  margin-top: auto;
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

/* --- Chat History（豆包式） --- */
.chat-history {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 8px 10px 8px;
  margin-top: 2px;
  overflow: hidden;
  gap: 8px;
}

.nav-menu-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.nav-attention-dot {
  flex-shrink: 0;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.nav-attention-dot.is-hitl_pending {
  background: var(--sun-amber);
  box-shadow: 0 0 0 2px var(--sun-amber-glow);
}

.nav-attention-dot.is-completed {
  background: #ef4444;
  box-shadow: 0 0 0 2px color-mix(in srgb, #ef4444 22%, transparent);
}

.nav-streaming-dot {
  flex-shrink: 0;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--sun-text-secondary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--sun-text-muted) 20%, transparent);
  animation: sidebar-stream-pulse 1.2s ease-in-out infinite;
}

@keyframes sidebar-stream-pulse {
  0%, 100% { opacity: 0.45; transform: scale(0.92); }
  50% { opacity: 1; transform: scale(1); }
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
