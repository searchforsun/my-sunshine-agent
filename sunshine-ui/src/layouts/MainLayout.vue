<script setup lang="ts">
import { useRouter, useRoute } from 'vue-router'
import { NLayout, NLayoutSider, NLayoutContent, NMenu, NText, NH1, type MenuOption } from 'naive-ui'
import { ChatbubblesOutline, BookOutline, StatsChartOutline } from '@vicons/ionicons5'
import { h, type Component } from 'vue'

const router = useRouter()
const route = useRoute()

function renderIcon(icon: Component) {
  return () => h(icon)
}

const menuOptions: MenuOption[] = [
  {
    label: 'AI 对话',
    key: 'chat',
    icon: renderIcon(ChatbubblesOutline),
  },
  {
    label: '知识库',
    key: 'knowledge',
    icon: renderIcon(BookOutline),
  },
  {
    label: '系统状态',
    key: 'status',
    icon: renderIcon(StatsChartOutline),
  },
]

function handleMenuClick(key: string) {
  router.push(`/${key}`)
}

const activeKey = route.name as string
</script>

<template>
  <NLayout has-sider style="height: 100vh">
    <NLayoutSider
      bordered
      collapse-mode="width"
      :width="220"
      style="background: var(--n-color); padding-top: 16px"
    >
      <div style="padding: 0 24px 20px; border-bottom: 1px solid var(--n-border-color)">
        <NH1 style="font-size: 18px; margin: 0">
          ☀️ Sunshine AI
        </NH1>
        <NText depth="3" style="font-size: 12px">企业级 AI 中台</NText>
      </div>
      <NMenu
        :value="activeKey"
        :options="menuOptions"
        @update:value="handleMenuClick"
        style="margin-top: 8px"
      />
    </NLayoutSider>

    <NLayoutContent>
      <router-view />
    </NLayoutContent>
  </NLayout>
</template>
