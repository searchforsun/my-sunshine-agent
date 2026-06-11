/**
 * 侧栏显隐（模块级单例，localStorage 持久化）
 */
import { ref, watch } from 'vue'

const STORAGE_KEY = 'sunshine-sidebar-visible'

const sidebarVisible = ref(localStorage.getItem(STORAGE_KEY) !== 'false')

let persisted = false

export function useSidebar() {
  if (!persisted) {
    persisted = true
    watch(sidebarVisible, (val) => {
      localStorage.setItem(STORAGE_KEY, String(val))
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

  return { sidebarVisible, toggleSidebar, showSidebar, hideSidebar }
}
