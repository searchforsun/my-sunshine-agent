import { computed, reactive, ref, watch } from 'vue'
import {
  CHAT_CONTENT_MIN_WIDTH,
  DRAWER_MIN_WIDTH as PLAN_DRAWER_MIN,
  PANE_MIN_WIDTH,
  splitRightDrawerBudget,
  usePlanNodeDrawer,
} from './usePlanNodeDrawer'
import { setSandboxDrawerLayout } from './sandboxDrawerBridge'

export interface SandboxWorkspaceDrawerPayload {
  conversationId: string
  /** 打开时聚焦的文件路径（可选） */
  focusPath?: string
}

/** 与 Chat / 节点抽屉统一 */
export const DRAWER_MIN_WIDTH = PANE_MIN_WIDTH
/** 首次打开偏好宽（可拖窄到 PANE_MIN） */
export const DRAWER_DEFAULT_WIDTH = 520
export { CHAT_CONTENT_MIN_WIDTH, PANE_MIN_WIDTH }
export const TREE_MIN_WIDTH = 160
export const TREE_MAX_WIDTH = 360
export const TREE_DEFAULT_WIDTH = 220
/** 预览区最小宽度；缩窄抽屉时树宽不超过 drawer - PREVIEW_MIN */
export const PREVIEW_MIN_WIDTH = 240

/**
 * 单开：预留 Chat；双开：预留 Chat + 当前节点抽屉宽。
 */
export function resolveSandboxDrawerMaxWidth(
  bodyW: number,
  bothOpen: boolean,
  planWidth = PLAN_DRAWER_MIN,
): number {
  if (bodyW <= 0) return DRAWER_MIN_WIDTH
  if (bothOpen) {
    const plan = Math.max(planWidth, PLAN_DRAWER_MIN)
    return Math.max(DRAWER_MIN_WIDTH, bodyW - CHAT_CONTENT_MIN_WIDTH - plan)
  }
  return Math.max(DRAWER_MIN_WIDTH, bodyW - CHAT_CONTENT_MIN_WIDTH)
}

const STORAGE_KEY = 'sunshine-sandbox-workspace-drawer-width'
const TREE_STORAGE_KEY = 'sunshine-sandbox-tree-width'

const state = reactive({
  open: false,
  conversationId: '' as string,
  focusPath: '' as string,
})

const savedWidth = ref(loadSavedWidth())
const savedTreeWidth = ref(loadSavedTreeWidth())
const chatBodyWidth = ref(0)
let chatBodyEl: HTMLElement | null = null
let bodyObserver: ResizeObserver | null = null

function loadSavedWidth(): number {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DRAWER_DEFAULT_WIDTH
    const n = Number(raw)
    if (Number.isFinite(n) && n >= DRAWER_MIN_WIDTH) return n
  } catch { /* ignore */ }
  return DRAWER_DEFAULT_WIDTH
}

function loadSavedTreeWidth(): number {
  try {
    const raw = localStorage.getItem(TREE_STORAGE_KEY)
    if (!raw) return TREE_DEFAULT_WIDTH
    const n = Number(raw)
    if (Number.isFinite(n) && n >= TREE_MIN_WIDTH && n <= TREE_MAX_WIDTH) return n
  } catch { /* ignore */ }
  return TREE_DEFAULT_WIDTH
}

function persistWidth(w: number) {
  try {
    localStorage.setItem(STORAGE_KEY, String(Math.round(w)))
  } catch { /* ignore */ }
}

function persistTreeWidth(w: number) {
  try {
    localStorage.setItem(TREE_STORAGE_KEY, String(Math.round(w)))
  } catch { /* ignore */ }
}

export function useSandboxWorkspaceDrawer() {
  const planDrawer = usePlanNodeDrawer()

  /** 节点 + 沙箱同时开（三栏：Chat | 节点 | 沙箱） */
  const compareMode = computed(
    () => state.open && planDrawer.state.open && !!planDrawer.state.node,
  )

  const drawerMaxWidth = computed(() =>
    resolveSandboxDrawerMaxWidth(
      chatBodyWidth.value,
      compareMode.value,
      planDrawer.drawerWidth.value,
    ),
  )

  const drawerWidth = computed(() => {
    const max = drawerMaxWidth.value
    return Math.min(Math.max(savedWidth.value, DRAWER_MIN_WIDTH), max)
  })

  const canResizeDrawer = computed(() => {
    // 双开：只要节点+沙箱总宽 > 两倍 min，就可以拖分界（不依赖 sandbox 是否已顶到「相对 Chat 的 max」）
    if (compareMode.value) {
      const budget = planDrawer.drawerWidth.value + drawerWidth.value
      return budget > PLAN_DRAWER_MIN + DRAWER_MIN_WIDTH
    }
    return drawerMaxWidth.value > DRAWER_MIN_WIDTH
  })

  const treeWidthMax = computed(() =>
    Math.max(TREE_MIN_WIDTH, Math.min(TREE_MAX_WIDTH, drawerWidth.value - PREVIEW_MIN_WIDTH)),
  )

  const treeWidth = computed(() =>
    Math.min(Math.max(savedTreeWidth.value, TREE_MIN_WIDTH), treeWidthMax.value),
  )

  const canResizeTree = computed(() => treeWidthMax.value > TREE_MIN_WIDTH)

  watch(drawerMaxWidth, (max) => {
    if (savedWidth.value > max) savedWidth.value = max
  })

  watch(treeWidthMax, (max) => {
    if (savedTreeWidth.value > max) savedTreeWidth.value = max
  })

  watch(
    [() => state.open, drawerWidth],
    () => setSandboxDrawerLayout(state.open, drawerWidth.value),
    { immediate: true },
  )

  function registerChatBody(el: HTMLElement | null) {
    bodyObserver?.disconnect()
    bodyObserver = null
    chatBodyEl = el
    if (!el) {
      chatBodyWidth.value = 0
      return
    }
    const sync = () => { chatBodyWidth.value = el.clientWidth }
    sync()
    bodyObserver = new ResizeObserver(sync)
    bodyObserver.observe(el)
  }

  function onResizePointerDown(e: PointerEvent) {
    if (!chatBodyEl) return
    const both = compareMode.value
    if (!both && !canResizeDrawer.value) return
    if (both) {
      const budget = planDrawer.drawerWidth.value + drawerWidth.value
      if (budget <= PLAN_DRAWER_MIN + DRAWER_MIN_WIDTH) return
    }
    e.preventDefault()
    const handle = e.currentTarget as HTMLElement
    handle.setPointerCapture(e.pointerId)
    document.body.classList.add('sandbox-drawer-resizing')
    const rightBudget = both
      ? planDrawer.drawerWidth.value + drawerWidth.value
      : 0

    const onMove = (ev: PointerEvent) => {
      if (!chatBodyEl) return
      const rect = chatBodyEl.getBoundingClientRect()
      const fromRight = rect.right - ev.clientX
      if (both) {
        // 只动节点↔沙箱分界，Chat 宽度不变
        const { plan, sandbox } = splitRightDrawerBudget(
          rightBudget,
          fromRight,
          PLAN_DRAWER_MIN,
          DRAWER_MIN_WIDTH,
        )
        savedWidth.value = sandbox
        planDrawer.setWidth(plan)
        return
      }
      const next = Math.min(
        Math.max(fromRight, DRAWER_MIN_WIDTH),
        drawerMaxWidth.value,
      )
      savedWidth.value = next
    }

    const onUp = (ev: PointerEvent) => {
      document.body.classList.remove('sandbox-drawer-resizing')
      handle.releasePointerCapture(ev.pointerId)
      handle.removeEventListener('pointermove', onMove)
      handle.removeEventListener('pointerup', onUp)
      handle.removeEventListener('pointercancel', onUp)
      persistWidth(drawerWidth.value)
      if (both) planDrawer.persistCurrentWidth()
    }

    handle.addEventListener('pointermove', onMove)
    handle.addEventListener('pointerup', onUp)
    handle.addEventListener('pointercancel', onUp)
  }

  function onTreeResizePointerDown(e: PointerEvent) {
    if (!canResizeTree.value) return
    e.preventDefault()
    const handle = e.currentTarget as HTMLElement
    handle.setPointerCapture(e.pointerId)
    document.body.classList.add('sandbox-tree-resizing')
    const startX = e.clientX
    const startW = treeWidth.value

    const onMove = (ev: PointerEvent) => {
      const next = Math.min(
        Math.max(startW + (ev.clientX - startX), TREE_MIN_WIDTH),
        treeWidthMax.value,
      )
      savedTreeWidth.value = next
    }

    const onUp = (ev: PointerEvent) => {
      document.body.classList.remove('sandbox-tree-resizing')
      handle.releasePointerCapture(ev.pointerId)
      handle.removeEventListener('pointermove', onMove)
      handle.removeEventListener('pointerup', onUp)
      handle.removeEventListener('pointercancel', onUp)
      persistTreeWidth(treeWidth.value)
    }

    handle.addEventListener('pointermove', onMove)
    handle.addEventListener('pointerup', onUp)
    handle.addEventListener('pointercancel', onUp)
  }

  function open(payload: SandboxWorkspaceDrawerPayload) {
    if (!payload.conversationId?.trim()) return
    state.conversationId = payload.conversationId.trim()
    state.focusPath = payload.focusPath?.trim() ?? ''
    state.open = true
  }

  function close() {
    state.open = false
    state.conversationId = ''
    state.focusPath = ''
  }

  return {
    state,
    open,
    close,
    compareMode,
    drawerWidth,
    drawerMaxWidth,
    canResizeDrawer,
    treeWidth,
    canResizeTree,
    registerChatBody,
    onResizePointerDown,
    onTreeResizePointerDown,
  }
}
