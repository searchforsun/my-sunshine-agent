<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, type MenuOption } from 'naive-ui'
import { ChatbubblesOutline, BookOutline, StatsChartOutline } from '@vicons/ionicons5'
import { h, type Component, computed } from 'vue'
import { useTheme } from '../composables/useTheme'
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
const isDark = computed(() => theme.value === 'dark')
const chatStore = useChatStore()

function handleNewChat() {
  chatStore.create()
  if (route.name !== 'chat') router.push('/chat')
}

function handleSwitchConversation(id: string) {
  chatStore.switchTo(id)
  if (route.name !== 'chat') router.push('/chat')
}

function handleDeleteConversation(id: string) {
  chatStore.remove(id)
}

function formatRelativeTime(ts: number): string {
  const diff = Date.now() - ts
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins}分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}天前`
  return new Date(ts).toLocaleDateString('zh-CN')
}
</script>

<template>
  <NLayout has-sider class="app-shell">
    <!-- Sidebar -->
    <NLayoutSider
      bordered
      :width="232"
      class="sidebar"
    >
      <!-- Brand -->
      <div class="brand">
        <div class="brand-icon">
          <svg width="28" height="28" viewBox="0 0 28 28" fill="none">
            <circle cx="14" cy="14" r="9" fill="url(#sun-grad)" />
            <g stroke="#f59e0b" stroke-width="1.6" stroke-linecap="round">
              <line x1="14" y1="2"  x2="14" y2="7" />
              <line x1="14" y1="21" x2="14" y2="26" />
              <line x1="2"  y1="14" x2="7"  y2="14" />
              <line x1="21" y1="14" x2="26" y2="14" />
              <line x1="5.5" y1="5.5"  x2="9"  y2="9" />
              <line x1="19"  y1="19"   x2="22.5" y2="22.5" />
              <line x1="5.5" y1="22.5" x2="9"  y2="19" />
              <line x1="19"  y1="9"    x2="22.5" y2="5.5" />
            </g>
            <defs>
              <radialGradient id="sun-grad" cx="30%" cy="30%">
                <stop offset="0%" stop-color="#fbbf24" />
                <stop offset="100%" stop-color="#f59e0b" />
              </radialGradient>
            </defs>
          </svg>
        </div>
        <div>
          <div class="brand-name">Sunshine AI</div>
          <div class="brand-sub">企业级 AI 中台</div>
        </div>
      </div>

      <!-- Nav -->
      <NMenu
        :value="activeKey"
        :options="menuOptions"
        @update:value="handleMenuClick"
        class="nav-menu"
      />

      <!-- Chat History -->
      <div class="chat-history" v-if="route.name === 'chat'">
        <div class="history-header">
          <span class="history-label">历史对话</span>
          <button class="new-chat-btn" @click="handleNewChat" title="新对话">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          </button>
        </div>
        <div class="history-list" v-if="chatStore.sortedConversations.length > 0">
          <div
            v-for="conv in chatStore.sortedConversations"
            :key="conv.id"
            class="history-item"
            :class="{ active: conv.id === chatStore.currentId }"
            @click="handleSwitchConversation(conv.id)"
          >
            <div class="history-item-content">
              <span class="history-item-title">{{ conv.title }}</span>
              <span class="history-item-time">{{ formatRelativeTime(conv.createdAt) }}</span>
            </div>
            <button
              class="history-item-delete"
              @click.stop="handleDeleteConversation(conv.id)"
              title="删除对话"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <polyline points="3 6 5 6 21 6" />
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
              </svg>
            </button>
          </div>
        </div>
        <div class="history-empty" v-else>
          <span class="history-empty-text">暂无历史对话</span>
        </div>
      </div>

      <!-- Footer -->
      <div class="sidebar-footer">
        <span class="pulse-dot online" />
        <span class="status-text">系统运行中</span>
        <span class="footer-spacer" />
        <button class="theme-toggle" @click="toggleTheme" :title="isDark ? '切换浅色模式' : '切换深色模式'">
          <!-- 太阳 -->
          <svg v-if="isDark" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="12" cy="12" r="5" />
            <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42" />
          </svg>
          <!-- 月亮 -->
          <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
          </svg>
        </button>
      </div>
    </NLayoutSider>

    <!-- Content -->
    <NLayoutContent class="content-area">
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
  gap: 12px;
  padding: 22px 20px 18px;
  border-bottom: 1px solid var(--sun-border);
  margin-bottom: 8px;
}

.brand-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sun-amber-glow);
  border-radius: 12px;
}

.brand-name {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--sun-text);
  line-height: 1.2;
}

.brand-sub {
  font-size: 11px;
  color: var(--sun-text-muted);
  letter-spacing: 0.5px;
  text-transform: uppercase;
  font-weight: 500;
}

/* --- Nav --- */
.nav-menu {
  flex-shrink: 0;
  margin-top: 4px;
  padding: 0 8px;
}

/* --- Footer --- */
.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  border-top: 1px solid var(--sun-border);
  margin-top: auto;
}

.status-text {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
}

.footer-spacer { flex: 1; }

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
  transition: all .15s;
  flex-shrink: 0;
}
.theme-toggle:hover {
  border-color: var(--sun-amber);
  color: var(--sun-amber-light);
  background: var(--sun-amber-glow);
}

/* --- Chat History --- */
.chat-history {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 4px 10px 4px 12px;
  border-top: 1px solid var(--sun-border);
  margin-top: 8px;
  overflow: hidden;
}

.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 4px 6px;
  flex-shrink: 0;
}

.history-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  color: var(--sun-text-muted);
}

.new-chat-btn {
  width: 28px; height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all .15s;
}
.new-chat-btn:hover {
  background: var(--sun-amber-glow);
  color: var(--sun-amber);
}

.history-list {
  flex: 1;
  overflow-y: auto;
  margin: 0 -4px;
  padding: 0 4px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all .15s;
  flex-shrink: 0;
}
.history-item:hover { background: var(--sun-surface-hover); }
.history-item.active { background: var(--sun-amber-glow); }
.history-item.active .history-item-title { color: var(--sun-amber); }

.history-item-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.history-item-title {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.3;
}

.history-item-time {
  font-size: 10.5px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
}

.history-item-delete {
  width: 24px; height: 24px;
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
  transition: all .15s;
}
.history-item:hover .history-item-delete { opacity: 0.5; }
.history-item-delete:hover {
  opacity: 1 !important;
  color: var(--sun-red);
  background: rgba(248, 113, 113, 0.1);
}

.history-empty {
  padding: 24px 8px;
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
</style>
