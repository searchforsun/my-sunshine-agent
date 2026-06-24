import { computed, reactive, ref, watch } from 'vue'
import type { ProcessingStep } from '../api/processingSteps'
import type { DagNodeView } from '../utils/planGraph'

export interface PlanNodeDrawerPayload {
  node: DagNodeView
  step?: ProcessingStep
}

/** 抽屉默认/最窄宽度（与历史行为一致） */
export const DRAWER_MIN_WIDTH = 400
/** chat-inner max-width(820) + 左右 padding(24×2)，主区低于此宽度时正文列开始收窄 */
export const CHAT_CONTENT_MIN_WIDTH = 868
const STORAGE_KEY = 'sunshine-plan-drawer-width'

const state = reactive({
  open: false,
  node: null as DagNodeView | null,
  step: undefined as ProcessingStep | undefined,
})

const savedWidth = ref(loadSavedWidth())
const chatBodyWidth = ref(0)
let chatBodyEl: HTMLElement | null = null
let bodyObserver: ResizeObserver | null = null

function loadSavedWidth(): number {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DRAWER_MIN_WIDTH
    const n = Number(raw)
    if (Number.isFinite(n) && n >= DRAWER_MIN_WIDTH) return n
  } catch { /* ignore */ }
  return DRAWER_MIN_WIDTH
}

function persistWidth(w: number) {
  try {
    localStorage.setItem(STORAGE_KEY, String(Math.round(w)))
  } catch { /* ignore */ }
}

const drawerMaxWidth = computed(() => {
  const bodyW = chatBodyWidth.value
  if (bodyW <= 0) return DRAWER_MIN_WIDTH
  return Math.max(DRAWER_MIN_WIDTH, bodyW - CHAT_CONTENT_MIN_WIDTH)
})

const drawerWidth = computed(() => {
  const max = drawerMaxWidth.value
  return Math.min(Math.max(savedWidth.value, DRAWER_MIN_WIDTH), max)
})

const canResizeDrawer = computed(() => drawerMaxWidth.value > DRAWER_MIN_WIDTH)

watch(drawerMaxWidth, (max) => {
  if (savedWidth.value > max) savedWidth.value = max
})

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
  if (!chatBodyEl || !canResizeDrawer.value) return
  e.preventDefault()
  const handle = e.currentTarget as HTMLElement
  handle.setPointerCapture(e.pointerId)
  document.body.classList.add('plan-drawer-resizing')

  const onMove = (ev: PointerEvent) => {
    if (!chatBodyEl) return
    const rect = chatBodyEl.getBoundingClientRect()
    const next = Math.min(
      Math.max(rect.right - ev.clientX, DRAWER_MIN_WIDTH),
      drawerMaxWidth.value,
    )
    savedWidth.value = next
  }

  const onUp = (ev: PointerEvent) => {
    document.body.classList.remove('plan-drawer-resizing')
    handle.releasePointerCapture(ev.pointerId)
    handle.removeEventListener('pointermove', onMove)
    handle.removeEventListener('pointerup', onUp)
    handle.removeEventListener('pointercancel', onUp)
    persistWidth(drawerWidth.value)
  }

  handle.addEventListener('pointermove', onMove)
  handle.addEventListener('pointerup', onUp)
  handle.addEventListener('pointercancel', onUp)
}

export function usePlanNodeDrawer() {
  function open(payload: PlanNodeDrawerPayload) {
    state.node = payload.node
    state.step = payload.step
    state.open = true
  }

  function close() {
    state.open = false
    state.node = null
    state.step = undefined
  }

  return {
    state,
    open,
    close,
    drawerWidth,
    canResizeDrawer,
    registerChatBody,
    onResizePointerDown,
  }
}
