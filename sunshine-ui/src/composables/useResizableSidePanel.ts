import { computed, onUnmounted, ref, watch, type Ref } from 'vue'

export const SIDE_PANEL_MIN_WIDTH = 360
const SIDE_PANEL_DEFAULT_WIDTH = 480

function loadWidth(storageKey: string): number {
  try {
    const raw = localStorage.getItem(storageKey)
    if (!raw) return SIDE_PANEL_DEFAULT_WIDTH
    const n = Number(raw)
    if (Number.isFinite(n) && n >= SIDE_PANEL_MIN_WIDTH) return n
  } catch { /* ignore */ }
  return SIDE_PANEL_DEFAULT_WIDTH
}

/** 左/右侧面板宽度拖拽（相对容器） */
export function useResizableSidePanel(
  storageKey: string,
  containerRef: Ref<HTMLElement | null>,
  side: 'left' | 'right' = 'left',
) {
  const savedWidth = ref(loadWidth(storageKey))
  const containerWidth = ref(0)
  let observer: ResizeObserver | null = null

  function bindContainer(el: HTMLElement | null) {
    observer?.disconnect()
    observer = null
    if (!el) {
      containerWidth.value = 0
      return
    }
    const sync = () => { containerWidth.value = el.clientWidth }
    sync()
    observer = new ResizeObserver(sync)
    observer.observe(el)
  }

  watch(containerRef, (el) => bindContainer(el), { immediate: true })
  onUnmounted(() => observer?.disconnect())

  const maxWidth = computed(() => {
    const cw = containerWidth.value
    if (cw <= 0) return SIDE_PANEL_DEFAULT_WIDTH
    return Math.max(SIDE_PANEL_MIN_WIDTH, Math.floor(cw * 0.62))
  })

  const panelWidth = computed(() =>
    Math.min(Math.max(savedWidth.value, SIDE_PANEL_MIN_WIDTH), maxWidth.value),
  )

  const canResize = computed(() => maxWidth.value > SIDE_PANEL_MIN_WIDTH)

  watch(maxWidth, (max) => {
    if (savedWidth.value > max) savedWidth.value = max
  })

  function onResizePointerDown(e: PointerEvent) {
    const container = containerRef.value
    if (!container || !canResize.value) return
    e.preventDefault()
    const handle = e.currentTarget as HTMLElement
    handle.setPointerCapture(e.pointerId)
    document.body.classList.add('eval-drawer-resizing')

    const onMove = (ev: PointerEvent) => {
      const rect = container.getBoundingClientRect()
      const next = side === 'left'
        ? Math.min(Math.max(ev.clientX - rect.left, SIDE_PANEL_MIN_WIDTH), maxWidth.value)
        : Math.min(Math.max(rect.right - ev.clientX, SIDE_PANEL_MIN_WIDTH), maxWidth.value)
      savedWidth.value = next
    }

    const onUp = (ev: PointerEvent) => {
      document.body.classList.remove('eval-drawer-resizing')
      handle.releasePointerCapture(ev.pointerId)
      handle.removeEventListener('pointermove', onMove)
      handle.removeEventListener('pointerup', onUp)
      handle.removeEventListener('pointercancel', onUp)
      try {
        localStorage.setItem(storageKey, String(Math.round(panelWidth.value)))
      } catch { /* ignore */ }
    }

    handle.addEventListener('pointermove', onMove)
    handle.addEventListener('pointerup', onUp)
    handle.addEventListener('pointercancel', onUp)
  }

  return { panelWidth, canResize, onResizePointerDown }
}
