/**
 * 主题切换（模块级单例，import 时即同步 DOM）
 */
import { nextTick, ref, watch } from 'vue'

type Theme = 'dark' | 'light'
const STORAGE_KEY = 'sunshine-theme'

function readTheme(): Theme {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'light' ? 'light' : 'dark'
}

export const theme = ref<Theme>(readTheme())

function applyTheme(val: Theme) {
  const root = document.documentElement
  root.classList.add('theme-switching')
  root.setAttribute('data-theme', val)
  root.style.colorScheme = val
  localStorage.setItem(STORAGE_KEY, val)
  // 等 Vue + Naive UI 同帧落地后再恢复 transition
  void nextTick(() => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        root.classList.remove('theme-switching')
      })
    })
  })
}

applyTheme(theme.value)
watch(theme, applyTheme)

export function useTheme() {
  function toggle() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }
  return { theme, toggle }
}
