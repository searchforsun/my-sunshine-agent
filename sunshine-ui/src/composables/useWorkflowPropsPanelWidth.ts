import { onUnmounted, ref } from 'vue'

const WIDTH_MIN = 280
const WIDTH_MAX = 560
const WIDTH_DEFAULT = 360

function clampWidth(w: number): number {
  const max = Math.min(WIDTH_MAX, Math.floor(window.innerWidth * 0.5))
  return Math.max(WIDTH_MIN, Math.min(max, w))
}

export function useWorkflowPropsPanelWidth() {
  const panelWidth = ref(WIDTH_DEFAULT)
  const resizing = ref(false)
  let startX = 0
  let startWidth = WIDTH_DEFAULT
  let onMove: ((ev: PointerEvent) => void) | null = null
  let onUp: (() => void) | null = null

  function cleanupListeners() {
    if (onMove) document.removeEventListener('pointermove', onMove)
    if (onUp) {
      document.removeEventListener('pointerup', onUp)
      document.removeEventListener('pointercancel', onUp)
    }
    onMove = null
    onUp = null
    resizing.value = false
    document.body.classList.remove('wf-props-resizing')
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
  }

  function onSplitterPointerDown(e: PointerEvent) {
    if (!e.isPrimary) return
    if (window.matchMedia('(max-width: 960px)').matches) return
    e.preventDefault()
    e.stopPropagation()
    cleanupListeners()
    resizing.value = true
    startX = e.clientX
    startWidth = panelWidth.value
    document.body.classList.add('wf-props-resizing')
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    onMove = (ev: PointerEvent) => {
      panelWidth.value = clampWidth(startWidth + (startX - ev.clientX))
    }
    onUp = () => cleanupListeners()
    document.addEventListener('pointermove', onMove)
    document.addEventListener('pointerup', onUp)
    document.addEventListener('pointercancel', onUp)
  }

  onUnmounted(cleanupListeners)

  return {
    panelWidth,
    resizing,
    onSplitterPointerDown,
  }
}
