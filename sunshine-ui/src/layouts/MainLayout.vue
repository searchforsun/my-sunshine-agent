<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, type MenuOption } from 'naive-ui'
import { ChatbubblesOutline, BookOutline, StatsChartOutline } from '@vicons/ionicons5'
import { h, type Component, computed } from 'vue'

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

      <!-- Footer -->
      <div class="sidebar-footer">
        <span class="pulse-dot online" />
        <span class="status-text">系统运行中</span>
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
  background: linear-gradient(180deg,
    rgba(17, 24, 39, 0.95) 0%,
    rgba(15, 18, 29, 0.98) 100%
  ) !important;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-right: 1px solid var(--sun-border) !important;
  display: flex;
  flex-direction: column;
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
  flex: 1;
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
}

.status-text {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
}

/* --- Content --- */
.content-area {
  background: radial-gradient(ellipse 60% 50% at 50% -10%, rgba(245, 158, 11, 0.03), transparent),
              var(--sun-black);
  overflow: auto;
}
</style>
