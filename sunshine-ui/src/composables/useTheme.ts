/**
 * 主题切换（模块级单例，import 时即同步 DOM）
 */
import { nextTick, ref, watch } from 'vue'
import hljsDarkUrl from 'highlight.js/styles/github-dark.css?url'
import hljsLightUrl from 'highlight.js/styles/github.css?url'

type Theme = 'dark' | 'light'
const STORAGE_KEY = 'sunshine-theme'
const HLJS_LINK_ID = 'sunshine-hljs-theme'

function readTheme(): Theme {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'light' ? 'light' : 'dark'
}

export const theme = ref<Theme>(readTheme())

function applyHljsTheme(val: Theme) {
  let link = document.getElementById(HLJS_LINK_ID) as HTMLLinkElement | null
  if (!link) {
    link = document.createElement('link')
    link.id = HLJS_LINK_ID
    link.rel = 'stylesheet'
    document.head.appendChild(link)
  }
  link.href = val === 'light' ? hljsLightUrl : hljsDarkUrl
}

function applyTheme(val: Theme) {
  const root = document.documentElement
  root.classList.add('theme-switching')
  root.setAttribute('data-theme', val)
  root.style.colorScheme = val
  localStorage.setItem(STORAGE_KEY, val)
  applyHljsTheme(val)
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
