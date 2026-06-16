<script setup lang="ts">
import { computed } from 'vue'
import { darkTheme, NConfigProvider, NDialogProvider, NGlobalStyle, NMessageProvider, type GlobalThemeOverrides } from 'naive-ui'
import { theme } from './composables/useTheme'

const isDark = computed(() => theme.value === 'dark')
const naiveTheme = computed(() => isDark.value ? darkTheme : null)

const darkOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#ececec',
    primaryColorHover: '#ffffff',
    primaryColorPressed: '#b4b4b4',
    primaryColorSuppl: '#ececec',
    bodyColor: '#212121',
    cardColor: '#2f2f2f',
    modalColor: '#2f2f2f',
    popoverColor: '#2f2f2f',
    inputColor: '#171717',
    borderColor: '#424242',
    dividerColor: '#424242',
    textColor1: '#ececec',
    textColor2: '#b4b4b4',
    textColor3: '#8e8e8e',
    hoverColor: '#383838',
    borderRadius: '10px',
  },
  Layout: {
    siderColor: 'transparent',
    siderBorderColor: '#424242',
    color: '#212121',
  },
  Menu: {
    itemTextColor: '#b4b4b4',
    itemColorActive: 'rgba(255, 255, 255, 0.08)',
    itemTextColorActive: '#ececec',
    itemColorActiveHover: 'rgba(255, 255, 255, 0.1)',
    itemColorHover: '#383838',
    borderRadius: '8px',
  },
  Button: {
    colorPrimary: '#ececec',
    colorHoverPrimary: '#ffffff',
    colorPressedPrimary: '#b4b4b4',
    textColorPrimary: '#212121',
    borderRadiusMedium: '10px',
    borderRadiusSmall: '8px',
    colorError: '#f85149',
    colorHoverError: '#ff6b6b',
    colorPressedError: '#da3633',
    textColorError: '#ffffff',
  },
  Dialog: {
    borderRadius: '12px',
    titleFontSize: '16px',
    titleFontWeight: '600',
    padding: '20px 24px 16px',
    contentMargin: '8px 0 0 0',
    iconSize: '0',
    border: 'none',
    color: '#2f2f2f',
    textColor: '#b4b4b4',
    titleTextColor: '#ececec',
  },
  Input: {
    border: '1px solid #424242',
    borderFocus: '1px solid #525252',
    borderHover: '1px solid #525252',
    color: '#171717',
    colorFocus: '#171717',
    textColor: '#ececec',
    placeholderColor: '#8e8e8e',
    borderRadius: '10px',
  },
  Card: {
    borderColor: '#424242',
    color: '#2f2f2f',
    borderRadius: '12px',
  },
  Scrollbar: {
    color: '#525252',
    colorHover: '#737373',
  },
}

const lightOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#0d0d0d',
    primaryColorHover: '#171717',
    primaryColorPressed: '#525252',
    primaryColorSuppl: '#0d0d0d',
    bodyColor: '#ffffff',
    cardColor: '#ffffff',
    modalColor: '#ffffff',
    popoverColor: '#ffffff',
    inputColor: '#f7f7f8',
    borderColor: '#e5e5e5',
    dividerColor: '#e5e5e5',
    textColor1: '#0d0d0d',
    textColor2: '#525252',
    textColor3: '#737373',
    hoverColor: '#f4f4f4',
    borderRadius: '10px',
  },
  Layout: {
    siderColor: 'transparent',
    siderBorderColor: '#e5e5e5',
    color: '#ffffff',
  },
  Menu: {
    itemTextColor: '#737373',
    itemColorActive: 'rgba(0, 0, 0, 0.06)',
    itemTextColorActive: '#0d0d0d',
    itemColorActiveHover: 'rgba(0, 0, 0, 0.08)',
    itemColorHover: '#f4f4f4',
    borderRadius: '8px',
  },
  Button: {
    colorPrimary: '#0d0d0d',
    colorHoverPrimary: '#171717',
    colorPressedPrimary: '#525252',
    textColorPrimary: '#ffffff',
    borderRadiusMedium: '10px',
    borderRadiusSmall: '8px',
    colorError: '#cf222e',
    colorHoverError: '#dc2626',
    colorPressedError: '#b91c1c',
    textColorError: '#ffffff',
  },
  Dialog: {
    borderRadius: '12px',
    titleFontSize: '16px',
    titleFontWeight: '600',
    padding: '20px 24px 16px',
    contentMargin: '8px 0 0 0',
    iconSize: '0',
    border: 'none',
    color: '#ffffff',
    textColor: '#525252',
    titleTextColor: '#0d0d0d',
  },
  Input: {
    border: '1px solid #e5e5e5',
    borderFocus: '1px solid #d4d4d4',
    borderHover: '1px solid #d4d4d4',
    color: '#ffffff',
    colorFocus: '#ffffff',
    textColor: '#0d0d0d',
    placeholderColor: '#737373',
    borderRadius: '10px',
  },
  Card: {
    borderColor: '#e5e5e5',
    color: '#ffffff',
    borderRadius: '12px',
  },
  Scrollbar: {
    color: '#d4d4d4',
    colorHover: '#a3a3a3',
  },
}

const themeOverrides = computed(() => isDark.value ? darkOverrides : lightOverrides)
</script>

<template>
  <NConfigProvider :theme="naiveTheme" :theme-overrides="themeOverrides">
    <NMessageProvider>
      <NDialogProvider>
        <NGlobalStyle />
        <router-view />
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>
