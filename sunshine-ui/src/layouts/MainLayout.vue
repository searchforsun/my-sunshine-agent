<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, useDialog, type MenuOption } from 'naive-ui'
import { ChatbubblesOutline, BookOutline, StatsChartOutline } from '@vicons/ionicons5'
import { h, type Component, computed, onMounted } from 'vue'
import { useTheme } from '../composables/useTheme'
import { useSidebar } from '../composables/useSidebar'
import { useChatStore } from '../stores/chatStore'

const router = useRouter()
const route = useRoute()

function renderIcon(icon: Component) {
  return () => h(icon)
}

const menuOptions: MenuOption[] = [
  { label: 'AI 对话', key: 'chat', icon: renderIcon(ChatbubblesOutline) },
  { label: '知识库',  key: 'knowledge', icon: renderIcon(BookOutline) },
  { label: '系统状态', key: 'status', icon: renderIcon(StatsChartOutline) },
]

function handleMenuClick(key: string) {
  router.push(`/${key}`)
}

const activeKey = computed(() => (route.name as string) || 'chat')
const { theme, toggle: toggleTheme } = useTheme()
const { sidebarVisible, toggleSidebar } = useSidebar()
const isDark = computed(() => theme.value === 'dark')
const chatStore = useChatStore()
const dialog = useDialog()

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
  chatStore.switchTo(id)
  if (route.name !== 'chat') router.push('/chat')
}

function handleDeleteConversation(id: string) {
  const conv = chatStore.conversations.find(c => c.id === id)
  const title = conv?.title || '该对话'
  dialog.create({
    class: 'sunshine-dialog',
    title: '永久删除对话',
    content: `确定删除「${title}」吗？\n此操作不可撤销，对话内容将永久删除且无法恢复。`,
    positiveText: '永久删除',
    negativeText: '取消',
    positiveButtonProps: { type: 'error', size: 'medium', round: true },
    negativeButtonProps: { size: 'medium', round: true },
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
      :width="232"
      class="sidebar"
    >
      <!-- Brand · 三足金乌 -->
      <div class="brand">
        <svg class="brand-mark" width="28" height="28" viewBox="0 0 28 28" fill="none" aria-hidden="true">
          <defs>
            <linearGradient id="jinwu-sun" x1="4" y1="2" x2="24" y2="26" gradientUnits="userSpaceOnUse">
              <stop stop-color="#fef9c3" />
              <stop offset="0.45" stop-color="#fbbf24" />
              <stop offset="1" stop-color="#c2410c" />
            </linearGradient>
          </defs>
          <rect width="28" height="28" rx="8" fill="url(#jinwu-sun)" />
          <g fill="white" fill-opacity="0.94">
            <path d="M14 8.5C8.8 8.5 4.5 10.6 3.2 13.4c3-1.4 6.2-2 10.8-2s7.8.6 10.8 2C22.5 10.6 19.2 8.5 14 8.5Z" />
            <ellipse cx="14" cy="11.8" rx="2" ry="2.3" />
            <path d="M13.2 9.6 11.6 8.2 13.8 10.2Z" />
            <ellipse cx="14" cy="14.8" rx="1.7" ry="2" />
          </g>
          <g stroke="white" stroke-opacity="0.94" stroke-width="1.1" stroke-linecap="round">
            <line x1="12.7" y1="16.2" x2="12.7" y2="20.8" />
            <line x1="14" y1="15.8" x2="14" y2="21.4" />
            <line x1="15.3" y1="16.2" x2="15.3" y2="20.8" />
          </g>
        </svg>
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

      <!-- 用户区（后续接入头像 / 登录信息） -->
      <div class="sidebar-user">
        <div class="user-avatar" aria-hidden="true">访</div>
        <div class="user-meta">
          <span class="user-name">访客用户</span>
          <span class="user-sub">demo-user</span>
        </div>
        <button class="theme-toggle" type="button" @click="toggleTheme" :title="isDark ? '切换浅色模式' : '切换深色模式'">
          <svg v-if="isDark" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="12" cy="12" r="5" />
            <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
          </svg>
          <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
          </svg>
        </button>
      </div>
    </NLayoutSider>

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
.app-shell {
  height: 100vh;
  background: var(--sun-black);
}

/* --- Sidebar --- */
.sidebar {
  background: var(--sun-sidebar-bg) !important;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-right: 1px solid var(--sun-border) !important;
  display: flex;
  flex-direction: column;
}

/* 与自定义边框统一，避免 Naive bordered 延迟换色 */
.sidebar :deep(.n-layout-sider-border) {
  background-color: var(--sun-border) !important;
}
/* Naive UI 内部滚动容器也需要 flex 列布局，否则 margin-top:auto 不生效 */
.sidebar :deep(.n-layout-sider-scroll-container) {
  display: flex;
  flex-direction: column;
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
  font-size: 16px;
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
  background: var(--sun-surface);
  color: var(--sun-text-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
  box-shadow: var(--shadow-card);
}

.sidebar-expand-fab:hover {
  border-color: var(--sun-amber);
  color: var(--sun-amber-light);
  background: var(--sun-amber-glow);
}

/* --- Nav --- */
.nav-menu {
  flex-shrink: 0;
  margin-top: 4px;
  padding: 0 8px;
}

.nav-menu :deep(.n-menu-item-content),
.nav-menu :deep(.n-menu-item-content__icon),
.nav-menu :deep(.n-menu-item-content-header) {
  transition: background-color 0.15s, color 0.15s !important;
}

/* --- 用户区 --- */
.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  margin-top: auto;
  border-top: 1px solid var(--sun-border);
}

.user-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--sun-amber), #d97706);
  color: #0f1722;
  font-size: 14px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-sub {
  font-size: 11px;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.theme-toggle {
  width: 28px; height: 28px;
  border-radius: 6px;
  border: 1px solid var(--sun-border);
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
  border-color: var(--sun-amber);
  color: var(--sun-amber-light);
  background: var(--sun-amber-glow);
}

/* --- Chat History（豆包式） --- */
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
  background: var(--sun-surface);
  color: var(--sun-text);
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.new-chat-primary:hover {
  border-color: var(--sun-amber);
  background: var(--sun-amber-glow);
  color: var(--sun-amber-light);
}

.history-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
  flex-shrink: 0;
}

.history-item:hover { background: var(--sun-surface-hover); }
.history-item.active { background: var(--sun-amber-glow); }
.history-item.active .history-item-title { color: var(--sun-amber-light); font-weight: 500; }

.history-item-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.35;
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
  padding: 20px 8px;
  text-align: center;
  flex-shrink: 0;
}

.history-empty-text {
  font-size: 12px;
  color: var(--sun-text-muted);
}

/* --- Content --- */
.content-area {
  background: radial-gradient(ellipse 60% 50% at 50% -10%, rgba(245, 158, 11, 0.03), transparent),
              var(--sun-black);
  overflow: auto;
}

.content-area--chat {
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.content-area--chat :deep(> *) {
  flex: 1;
  min-height: 0;
}
</style>
