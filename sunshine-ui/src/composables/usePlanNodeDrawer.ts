import { computed, reactive, ref, watch } from 'vue'
import type { PlanGraph } from '../api/executionPlans'
import type { ProcessingStep } from '../api/processingSteps'
import type { DagNodeView } from '../utils/planGraph'
import { sandboxDrawerLayout } from './sandboxDrawerBridge'
import { useViewportMode } from './useViewportMode'
import { pushRightDrawer, removeRightDrawer, registerRightDrawerClose, rightDrawerZIndex } from './rightDrawerStack'

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
/** 紧凑档浮层宽度下限（可拖窄，松手不持久化到并排偏好） */
export const OVERLAY_DRAWER_MIN_WIDTH = 360
const STORAGE_KEY = 'sunshine-plan-drawer-width'

const state = reactive({
  open: false,
  activePlanId: null as string | null,
  userQuery: '',
  node: null as DagNodeView | null,
  step: undefined as ProcessingStep | undefined,
  graph: null as PlanGraph | null,
})

/**
 * 层级栈：抽屉内嵌套卡（worker 抽屉 → 子 agent 卡 → 子子 agent）open 时入栈，
 * 右上角关闭按钮逐层返回，最顶层才真正关闭抽屉。
 * 多级下钻是同一面板，不占右侧堆栈栈位。
 */
const history = [] as PlanNodeDrawerPayload[]
/** 层级深度：>1 时右上角「返回上级」，=1 时「收起」（history 为普通数组，显式维护响应式深度） */
const depth = ref(0)

function syncDepth() {
  depth.value = history.length
}

function applyHistoryEntry(payload: PlanNodeDrawerPayload) {
  state.activePlanId = payload.planId
  state.userQuery = payload.userQuery?.trim() ?? ''
  state.node = payload.node
  state.step = payload.step
  state.graph = payload.graph ?? null
  state.open = true
}

function close() {
  history.splice(0, history.length)
  syncDepth()
  state.open = false
  state.activePlanId = null
  state.userQuery = ''
  state.node = null
  state.step = undefined
  state.graph = null
  removeRightDrawer('plan')
}

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

const savedWidth = ref(loadSavedWidth())
const chatBodyWidth = ref(0)
let chatBodyEl: HTMLElement | null = null
let bodyObserver: ResizeObserver | null = null
const { isNarrowViewport, isCompactViewport } = useViewportMode()
/** 并排挤占仅在「四面板最小宽放得下」的宽敞档成立；其余宽度抽屉浮层化 */
const overlayMode = computed(() => isNarrowViewport.value || isCompactViewport.value)

const drawerWidth = computed(() => {
  if (overlayMode.value) return 0
  const max = drawerMaxWidth.value
  return Math.min(Math.max(savedWidth.value, DRAWER_MIN_WIDTH), max)
})

const canResizeDrawer = computed(() => {
  if (overlayMode.value) return !isNarrowViewport.value
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
  if (!canResizeDrawer.value) return
  if (!overlayMode.value && !chatBodyEl) return
  e.preventDefault()
  const handle = e.currentTarget as HTMLElement
  handle.setPointerCapture(e.pointerId)
  document.body.classList.add('plan-drawer-resizing')
  const aside = handle.closest('aside')

  if (overlayMode.value) {
    // 浮层：拖的是自身绝对宽度，与布局预算无关
    const startX = e.clientX
    const startW = (aside?.getBoundingClientRect().width) || DRAWER_MIN_WIDTH
    const onMove = (ev: PointerEvent) => {
      savedWidth.value = Math.min(
        Math.max(startW + (startX - ev.clientX), OVERLAY_DRAWER_MIN_WIDTH),
        Math.max(OVERLAY_DRAWER_MIN_WIDTH, Math.round(window.innerWidth * 0.66)),
      )
    }
    const onUp = (ev: PointerEvent) => {
      document.body.classList.remove('plan-drawer-resizing')
      handle.releasePointerCapture(ev.pointerId)
      handle.removeEventListener('pointermove', onMove)
      handle.removeEventListener('pointerup', onUp)
      handle.removeEventListener('pointercancel', onUp)
    }
    handle.addEventListener('pointermove', onMove)
    handle.addEventListener('pointerup', onUp)
    handle.addEventListener('pointercancel', onUp)
    return
  }

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

registerRightDrawerClose('plan', close)

export function usePlanNodeDrawer() {
  /**
   * @param options.push 抽屉内嵌套卡下钻（worker 抽屉 → 子 agent 卡 → 子子 agent）入栈；
   * 主时间线 / DAG 画布平级打开重置为单层（新浏览上下文）。
   */
  function open(payload: PlanNodeDrawerPayload, options: { push?: boolean } = {}) {
    if (!state.open) {
      history.splice(0, history.length, payload)
    } else if (options.push) {
      const top = history[history.length - 1]
      if (top?.planId === payload.planId) {
        history[history.length - 1] = payload
      } else {
        history.push(payload)
      }
    } else {
      history.splice(0, history.length, payload)
    }
    syncDepth()
    applyHistoryEntry(payload)
    // 浮层档：面板入右侧堆栈（容量 2，超额 FIFO 淘汰最早面板并触发其关闭）；并排档不占栈
    if (overlayMode.value) {
      removeRightDrawer('plan')
      pushRightDrawer('plan')
    }
  }

  /** 有父层则返回上一层；最顶层直接关闭抽屉 */
  function goBack() {
    if (history.length > 1) {
      history.pop()
      syncDepth()
      applyHistoryEntry(history[history.length - 1])
    } else {
      close()
    }
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
    goBack,
    depth,
    isActivePlan,
    drawerWidth,
    drawerMaxWidth,
    canResizeDrawer,
    registerChatBody,
    onResizePointerDown,
    setWidth,
    persistCurrentWidth,
    overlayMode,
    zIndex: computed(() => rightDrawerZIndex('plan')),
  }
}
