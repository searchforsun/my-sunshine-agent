/**
 * 主题切换组合式函数（模块级单例）
 */
import { ref, watch } from 'vue'

type Theme = 'dark' | 'light'
const STORAGE_KEY = 'sunshine-theme'

// 模块级单例 — 所有 useTheme() 调用共享同一状态
const theme = ref<Theme>(
  (localStorage.getItem(STORAGE_KEY) as Theme) || 'dark'
)

let applied = false

export function useTheme() {
  if (!applied) {
    document.documentElement.setAttribute('data-theme', theme.value)
    applied = true
    watch(theme, (val) => {
      document.documentElement.setAttribute('data-theme', val)
      localStorage.setItem(STORAGE_KEY, val)
    })
  }

  function toggle() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  return { theme, toggle }
}
