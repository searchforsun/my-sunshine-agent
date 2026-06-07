<script setup lang="ts">
import { darkTheme, NConfigProvider, NGlobalStyle, type GlobalThemeOverrides } from 'naive-ui'
import MainLayout from './layouts/MainLayout.vue'

const themeOverrides: GlobalThemeOverrides = {
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
</script>

<template>
  <NConfigProvider :theme="darkTheme" :theme-overrides="themeOverrides">
    <NGlobalStyle />
    <MainLayout />
  </NConfigProvider>
</template>
