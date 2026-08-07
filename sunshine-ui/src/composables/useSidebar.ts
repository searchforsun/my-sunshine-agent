/**
 * 侧栏显隐 + 动态宽度（模块级单例，localStorage 持久化）
 */
import { ref, watch } from 'vue'

const VISIBLE_KEY = 'sunshine-sidebar-visible'
const WIDTH_KEY = 'sunshine-sidebar-width'

export const SIDEBAR_MIN_WIDTH = 220
export const SIDEBAR_MAX_WIDTH = 420
export const SIDEBAR_DEFAULT_WIDTH = 280

const sidebarVisible = ref(localStorage.getItem(VISIBLE_KEY) !== 'false')

function loadWidth(): number {
  try {
    const raw = localStorage.getItem(WIDTH_KEY)
    if (raw) {
      const n = Number(raw)
      if (Number.isFinite(n) && n >= SIDEBAR_MIN_WIDTH && n <= SIDEBAR_MAX_WIDTH) return n
    }
  } catch { /* ignore */ }
  return SIDEBAR_DEFAULT_WIDTH
}

const sidebarWidth = ref(loadWidth())

let persisted = false

export function useSidebar() {
  if (!persisted) {
    persisted = true
    watch(sidebarVisible, (val) => {
      localStorage.setItem(VISIBLE_KEY, String(val))
    })
    watch(sidebarWidth, (val) => {
      localStorage.setItem(WIDTH_KEY, String(val))
    })
  }

  function toggleSidebar() {
    sidebarVisible.value = !sidebarVisible.value
  }

  function showSidebar() {
    sidebarVisible.value = true
  }

  function hideSidebar() {
    sidebarVisible.value = false
  }

  return { sidebarVisible, sidebarWidth, toggleSidebar, showSidebar, hideSidebar }
}
