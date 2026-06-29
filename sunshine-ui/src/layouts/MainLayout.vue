<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, NDropdown, NIcon, useDialog, type MenuOption, type DropdownOption } from 'naive-ui'
import { ChatbubblesOutline, BookOutline, StatsChartOutline, SettingsOutline, LogOutOutline, EllipsisHorizontal, LayersOutline } from '@vicons/ionicons5'
import { h, type Component, computed, onMounted, ref } from 'vue'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import BrandMark from '../components/BrandMark.vue'
import UserSettingsModal from '../components/UserSettingsModal.vue'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

function renderIcon(icon: Component) {
  return () => h(icon)
}

function renderDropdownIcon(icon: Component) {
  return () => h(NIcon, { size: 16 }, { default: () => h(icon) })
}

const menuOptions: MenuOption[] = [
  { label: 'AI 对话', key: 'chat', icon: renderIcon(ChatbubblesOutline) },
  { label: '知识库',  key: 'knowledge', icon: renderIcon(BookOutline) },
  { label: 'Skills', key: 'skills', icon: renderIcon(LayersOutline) },
  { label: '系统状态', key: 'status', icon: renderIcon(StatsChartOutline) },
]

function handleMenuClick(key: string) {
  router.push(`/${key}`)
}

const activeKey = computed(() => {
  if (route.name === 'skill-diff') return 'skills'
  return (route.name as string) || 'chat'
})
const { theme, toggle: toggleTheme } = useTheme()
const { sidebarVisible, toggleSidebar } = useSidebar()
const isDark = computed(() => theme.value === 'dark')
const chatStore = useChatStore()
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
    await chatStore.switchTo(id)
    if (route.name !== 'chat') router.push('/chat')
  })()
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

      <!-- Nav -->
      <NMenu
        :value="activeKey"
        :options="menuOptions"
        @update:value="handleMenuClick"
        class="nav-menu"
      />

      <!-- Chat History（豆包式侧栏） -->
      <div class="chat-history" v-if="route.name === 'chat'">
        <button class="new-chat-primary" @click="handleNewChat">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新对话
        </button>
        <div class="history-list" v-if="chatStore.sortedConversations.length > 0">
          <div
            v-for="conv in chatStore.sortedConversations"
            :key="conv.id"
            class="history-item"
            :class="{ active: conv.id === chatStore.currentId }"
            @click="handleSwitchConversation(conv.id)"
          >
            <span class="history-item-title">{{ conv.title }}</span>
            <button
              class="history-item-delete"
              @click.stop="handleDeleteConversation(conv.id)"
              title="删除对话"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </div>
        <div class="history-empty" v-else>
          <span class="history-empty-text">暂无对话</span>
        </div>
      </div>
      <div v-else class="sidebar-spacer" aria-hidden="true" />

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
    <NLayoutContent class="content-area" :class="{ 'content-area--chat': route.name === 'chat' }">
      <button
        v-if="!sidebarVisible && route.name !== 'chat'"
        type="button"
        class="sidebar-expand-fab"
        title="显示侧栏"
        @click="toggleSidebar"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <line x1="9" y1="3" x2="9" y2="21" />
          <polyline points="10 8 13 12 10 16" />
        </svg>
      </button>
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

.sidebar-expand-fab {
  position: fixed;
  top: 14px;
  left: 14px;
  z-index: 100;
  width: 36px;
  height: 36px;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, color 0.15s;
  box-shadow: var(--shadow-card);
}

.sidebar-expand-fab:hover {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

/* --- Nav --- */
.nav-menu {
  flex-shrink: 0;
  margin-top: 4px;
  padding: 0 8px;
}

.nav-menu :deep(.n-menu) {
  --n-color: transparent !important;
  --n-item-color-hover: var(--sun-row-hover) !important;
  --n-item-color-active: var(--sun-row-hover) !important;
  --n-item-color-active-hover: var(--sun-row-hover) !important;
}

.nav-menu :deep(.n-menu-item-content) {
  border-radius: var(--radius-sm);
  transition: background 0.15s, color 0.15s;
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
.sidebar-spacer {
  flex: 1;
  min-height: 0;
}

.chat-history {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 12px 10px 8px;
  margin-top: 4px;
  overflow: hidden;
  gap: 8px;
}

.new-chat-primary {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--sun-border);
  border-radius: 10px;
  background: transparent;
  color: var(--sun-text);
  font-size: var(--sun-font-base);
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.new-chat-primary:hover {
  border-color: var(--sun-border-light);
  background: var(--sun-row-hover);
}

.history-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--sun-border);
}

.history-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}

.history-item:hover { background: var(--sun-row-hover); }
.history-item.active {
  background: var(--sun-row-hover);
}
.history-item.active .history-item-title {
  color: var(--sun-text);
  font-weight: 500;
}

.history-item-title {
  flex: 1;
  min-width: 0;
  font-size: var(--sun-font-base);
  font-weight: 400;
  color: var(--sun-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: var(--sun-line);
}

.history-item-delete {
  width: 22px;
  height: 22px;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  opacity: 0;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.history-item:hover .history-item-delete { opacity: 0.45; }
.history-item-delete:hover {
  opacity: 1 !important;
  color: var(--sun-red);
  background: rgba(248, 113, 113, 0.12);
}

.history-empty {
  margin-top: 4px;
  padding: 20px 8px 4px;
  text-align: center;
  flex-shrink: 0;
  border-top: 1px solid var(--sun-border);
}

.history-empty-text {
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

/* --- Content --- */
.content-area {
  background: var(--sun-black);
  overflow: auto;
  height: 100%;
  min-height: 0;
}

.content-area--chat {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.content-area--chat :deep(> *) {
  flex: 1;
  min-height: 0;
}
</style>
