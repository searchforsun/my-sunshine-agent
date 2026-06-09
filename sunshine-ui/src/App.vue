<script setup lang="ts">
import { computed } from 'vue'
import { darkTheme, NConfigProvider, NGlobalStyle, type GlobalThemeOverrides } from 'naive-ui'
import MainLayout from './layouts/MainLayout.vue'
import { useTheme } from './composables/useTheme'

const { theme } = useTheme()
const isDark = computed(() => theme.value === 'dark')
const naiveTheme = computed(() => isDark.value ? darkTheme : null)

const darkOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#f59e0b',
    primaryColorHover: '#fbbf24',
    primaryColorPressed: '#d97706',
    primaryColorSuppl: '#f59e0b',
    bodyColor: '#0f1722',
    cardColor: '#1a2332',
    modalColor: '#1a2332',
    popoverColor: '#1a2332',
    inputColor: '#111827',
    borderColor: '#263348',
    dividerColor: '#263348',
  },
  Layout: {
    siderColor: 'rgba(17, 24, 39, 0.85)',
    siderBorderColor: '#263348',
  },
  Menu: {
    itemTextColor: '#94a3b8',
    itemColorActive: 'rgba(245, 158, 11, 0.12)',
    itemTextColorActive: '#f59e0b',
    itemColorActiveHover: 'rgba(245, 158, 11, 0.18)',
  },
  Button: {
    colorPrimary: '#f59e0b',
    colorHoverPrimary: '#fbbf24',
    colorPressedPrimary: '#d97706',
    textColorPrimary: '#0f1722',
  },
  Input: {
    border: '1px solid #263348',
    borderFocus: '1px solid #f59e0b',
    borderHover: '1px solid #334155',
  },
  Card: {
    borderColor: '#263348',
    color: '#1a2332',
  },
  Scrollbar: {
    color: '#334155',
    colorHover: '#64748b',
  },
}

const lightOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#f59e0b',
    primaryColorHover: '#fbbf24',
    primaryColorPressed: '#d97706',
    primaryColorSuppl: '#f59e0b',
    bodyColor: '#f8fafc',
    cardColor: '#ffffff',
    modalColor: '#ffffff',
    popoverColor: '#ffffff',
    inputColor: '#f1f5f9',
    borderColor: '#e2e8f0',
    dividerColor: '#e2e8f0',
  },
  Layout: {
    siderColor: 'rgba(255, 255, 255, 0.95)',
    siderBorderColor: '#e2e8f0',
  },
  Menu: {
    itemTextColor: '#64748b',
    itemColorActive: 'rgba(245, 158, 11, 0.1)',
    itemTextColorActive: '#d97706',
    itemColorActiveHover: 'rgba(245, 158, 11, 0.15)',
  },
  Button: {
    colorPrimary: '#f59e0b',
    colorHoverPrimary: '#fbbf24',
    colorPressedPrimary: '#d97706',
    textColorPrimary: '#ffffff',
  },
  Input: {
    border: '1px solid #e2e8f0',
    borderFocus: '1px solid #f59e0b',
    borderHover: '1px solid #cbd5e1',
  },
  Card: {
    borderColor: '#e2e8f0',
    color: '#ffffff',
  },
  Scrollbar: {
    color: '#cbd5e1',
    colorHover: '#94a3b8',
  },
}

const themeOverrides = computed(() => isDark.value ? darkOverrides : lightOverrides)
</script>

<template>
  <NConfigProvider :theme="naiveTheme" :theme-overrides="themeOverrides">
    <NGlobalStyle />
    <MainLayout />
  </NConfigProvider>
</template>
