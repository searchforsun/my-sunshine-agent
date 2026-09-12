/**
 * 侧栏显隐 + 动态宽度（模块级单例，localStorage 持久化）
 */
import { ref, watch } from 'vue'
import { useViewportMode } from './useViewportMode'

const VISIBLE_KEY = 'sunshine-sidebar-visible'
const WIDTH_KEY = 'sunshine-sidebar-width'

export const SIDEBAR_MIN_WIDTH = 220
export const SIDEBAR_MAX_WIDTH = 420
export const SIDEBAR_DEFAULT_WIDTH = 280

/** 窄屏（手机/平板竖屏）堆栈式布局：菜单一屏，由页头按钮唤出，不持久化显隐偏好 */
const NARROW_VIEWPORT_QUERY = '(max-width: 768px)'

const { isNarrowViewport } = useViewportMode()

const sidebarVisible = ref(
  window.matchMedia(NARROW_VIEWPORT_QUERY).matches
    ? false
    : localStorage.getItem(VISIBLE_KEY) !== 'false',
)

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
    // 窄屏下显隐由视口模式决定，不持久化；切回桌面后恢复用户持久化偏好
    watch(sidebarVisible, (val) => {
      if (!isNarrowViewport.value) localStorage.setItem(VISIBLE_KEY, String(val))
    })
    watch(sidebarWidth, (val) => {
      localStorage.setItem(WIDTH_KEY, String(val))
    })
    watch(isNarrowViewport, (narrow) => {
      sidebarVisible.value = narrow ? false : localStorage.getItem(VISIBLE_KEY) !== 'false'
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
