<script setup lang="ts">
import { computed } from 'vue'
import { darkTheme, NConfigProvider, NDialogProvider, NGlobalStyle, type GlobalThemeOverrides } from 'naive-ui'
import MainLayout from './layouts/MainLayout.vue'
import { theme } from './composables/useTheme'

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
    textColor1: '#e2e8f0',
    textColor2: '#94a3b8',
    textColor3: '#64748b',
    hoverColor: '#1f2b3d',
  },
  Layout: {
    siderColor: 'transparent',
    siderBorderColor: '#263348',
    color: '#090d14',
  },
  Menu: {
    itemTextColor: '#94a3b8',
    itemColorActive: 'rgba(245, 158, 11, 0.12)',
    itemTextColorActive: '#fbbf24',
    itemColorActiveHover: 'rgba(245, 158, 11, 0.18)',
    itemColorHover: '#1f2b3d',
  },
  Button: {
    colorPrimary: '#f59e0b',
    colorHoverPrimary: '#fbbf24',
    colorPressedPrimary: '#d97706',
    textColorPrimary: '#0f1722',
    borderRadiusMedium: '10px',
    borderRadiusSmall: '8px',
    colorError: '#dc2626',
    colorHoverError: '#ef4444',
    colorPressedError: '#b91c1c',
    textColorError: '#ffffff',
  },
  Dialog: {
    borderRadius: '14px',
    titleFontSize: '16px',
    titleFontWeight: '600',
    padding: '22px 24px 18px',
    contentMargin: '10px 0 0 0',
    iconSize: '22px',
  },
  Input: {
    border: '1px solid #263348',
    borderFocus: '1px solid #f59e0b',
    borderHover: '1px solid #334155',
    color: '#e2e8f0',
    colorFocus: '#e2e8f0',
    placeholderColor: '#64748b',
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
    textColor1: '#0f172a',
    textColor2: '#475569',
    textColor3: '#94a3b8',
    hoverColor: '#f8fafc',
  },
  Layout: {
    siderColor: 'transparent',
    siderBorderColor: '#e2e8f0',
    color: '#f8fafc',
  },
  Menu: {
    itemTextColor: '#64748b',
    itemColorActive: 'rgba(245, 158, 11, 0.1)',
    itemTextColorActive: '#d97706',
    itemColorActiveHover: 'rgba(245, 158, 11, 0.15)',
    itemColorHover: '#f8fafc',
  },
  Button: {
    colorPrimary: '#f59e0b',
    colorHoverPrimary: '#fbbf24',
    colorPressedPrimary: '#d97706',
    textColorPrimary: '#ffffff',
    borderRadiusMedium: '10px',
    borderRadiusSmall: '8px',
    colorError: '#dc2626',
    colorHoverError: '#ef4444',
    colorPressedError: '#b91c1c',
    textColorError: '#ffffff',
  },
  Dialog: {
    borderRadius: '14px',
    titleFontSize: '16px',
    titleFontWeight: '600',
    padding: '22px 24px 18px',
    contentMargin: '10px 0 0 0',
    iconSize: '22px',
  },
  Input: {
    border: '1px solid #e2e8f0',
    borderFocus: '1px solid #f59e0b',
    borderHover: '1px solid #cbd5e1',
    color: '#0f172a',
    colorFocus: '#0f172a',
    placeholderColor: '#94a3b8',
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
    <NDialogProvider>
      <NGlobalStyle />
      <MainLayout />
    </NDialogProvider>
  </NConfigProvider>
</template>
