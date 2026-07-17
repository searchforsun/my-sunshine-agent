import { computed, reactive, ref, watch } from 'vue'
import type { PlanGraph } from '../api/executionPlans'
import type { ProcessingStep } from '../api/processingSteps'
import type { DagNodeView } from '../utils/planGraph'
import { sandboxDrawerLayout } from './sandboxDrawerBridge'

export interface PlanNodeDrawerPayload {
  planId: string
  userQuery?: string
  node: DagNodeView
  step?: ProcessingStep
  /** 执行 Plan 拓扑（条件分支出边等抽屉展示用） */
  graph?: PlanGraph
}

/**
 * Chat / 节点抽屉 / 沙箱 三栏统一最小宽（底栏四控件单行）。
 */
export const PANE_MIN_WIDTH = 420
export const DRAWER_MIN_WIDTH = PANE_MIN_WIDTH
export const PLAN_COMPARE_MIN = PANE_MIN_WIDTH
export const CHAT_CONTENT_MIN_WIDTH = PANE_MIN_WIDTH
export const SANDBOX_DRAWER_MIN_WIDTH = PANE_MIN_WIDTH
const STORAGE_KEY = 'sunshine-plan-drawer-width'

const state = reactive({
  open: false,
  activePlanId: null as string | null,
  userQuery: '',
  node: null as DagNodeView | null,
  step: undefined as ProcessingStep | undefined,
  graph: null as PlanGraph | null,
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

/** 双开时预留 Chat + 当前沙箱宽（联动拖动时用实际宽，而非仅 min） */
export function resolvePlanDrawerMaxWidth(
  bodyW: number,
  sandboxOpen: boolean,
  sandboxWidth = SANDBOX_DRAWER_MIN_WIDTH,
): number {
  if (bodyW <= 0) return DRAWER_MIN_WIDTH
  if (sandboxOpen) {
    const sb = Math.max(sandboxWidth, SANDBOX_DRAWER_MIN_WIDTH)
    return Math.max(DRAWER_MIN_WIDTH, bodyW - CHAT_CONTENT_MIN_WIDTH - sb)
  }
  return Math.max(DRAWER_MIN_WIDTH, bodyW - CHAT_CONTENT_MIN_WIDTH)
}

/** 双开时拖沙箱左缘：在右侧预算内拆分节点/沙箱，Chat 宽度不变 */
export function splitRightDrawerBudget(
  rightBudget: number,
  sandboxFromRightEdge: number,
  planMin: number,
  sandboxMin: number,
): { plan: number; sandbox: number } {
  const budget = Math.max(rightBudget, planMin + sandboxMin)
  const sandbox = Math.min(
    Math.max(sandboxFromRightEdge, sandboxMin),
    budget - planMin,
  )
  return { plan: budget - sandbox, sandbox }
}

const drawerMaxWidth = computed(() =>
  resolvePlanDrawerMaxWidth(
    chatBodyWidth.value,
    sandboxDrawerLayout.open,
    sandboxDrawerLayout.width,
  ),
)

const drawerWidth = computed(() => {
  const max = drawerMaxWidth.value
  return Math.min(Math.max(savedWidth.value, DRAWER_MIN_WIDTH), max)
})

const canResizeDrawer = computed(() => {
  // 双开时左缘：只要还能从 Chat 再挤一点，或右侧预算仍大于两倍 min（可与沙箱对挤）
  if (sandboxDrawerLayout.open) {
    const rightBudget = drawerWidth.value + sandboxDrawerLayout.width
    if (rightBudget > DRAWER_MIN_WIDTH + SANDBOX_DRAWER_MIN_WIDTH) return true
  }
  return drawerMaxWidth.value > DRAWER_MIN_WIDTH
})

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
  const aside = handle.closest('aside')

  const onMove = (ev: PointerEvent) => {
    if (!chatBodyEl) return
    // 双开时节点在中间：以 aside 右缘为锚（右侧是沙箱）；单开时等同 body 右缘
    const rightEdge = aside?.getBoundingClientRect().right
      ?? chatBodyEl.getBoundingClientRect().right
    const next = Math.min(
      Math.max(rightEdge - ev.clientX, DRAWER_MIN_WIDTH),
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
    state.activePlanId = payload.planId
    state.userQuery = payload.userQuery?.trim() ?? ''
    state.node = payload.node
    state.step = payload.step
    state.graph = payload.graph ?? null
    state.open = true
  }

  function close() {
    state.open = false
    state.activePlanId = null
    state.userQuery = ''
    state.node = null
    state.step = undefined
    state.graph = null
  }

  function isActivePlan(planId: string | undefined) {
    return !!planId && state.open && state.activePlanId === planId
  }

  /** 双开联动调宽（不经 max 钳制到「预留沙箱」以免互相打架） */
  function setWidth(w: number) {
    savedWidth.value = Math.max(DRAWER_MIN_WIDTH, Math.round(w))
  }

  function persistCurrentWidth() {
    persistWidth(drawerWidth.value)
  }

  return {
    state,
    open,
    close,
    isActivePlan,
    drawerWidth,
    drawerMaxWidth,
    canResizeDrawer,
    registerChatBody,
    onResizePointerDown,
    setWidth,
    persistCurrentWidth,
  }
}
