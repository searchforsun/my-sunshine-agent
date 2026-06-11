/**
 * Mermaid 全屏查看器 — 固定 viewBox + SVG 尺寸缩放（整图放大，非裁切）
 */
import { createToolButton } from './toolIcons'

const MIN_ZOOM = 0.15
const MAX_ZOOM = 12
export const MERMAID_ZOOM_STEP = 1.2
const VIEWPORT_PAD = 24

export interface MermaidOverlayViewer {
  destroy: () => void
  zoomIn: () => void
  zoomOut: () => void
  reset: () => void
}

export interface ViewBox {
  x: number
  y: number
  w: number
  h: number
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

function readDeclaredViewBox(svg: SVGSVGElement): ViewBox | null {
  const attr = svg.getAttribute('viewBox')
  if (attr) {
    const parts = attr.trim().split(/[\s,]+/).map(Number)
    if (parts.length === 4 && parts.every(Number.isFinite) && parts[2] > 0 && parts[3] > 0) {
      return { x: parts[0], y: parts[1], w: parts[2], h: parts[3] }
    }
  }
  const vb = svg.viewBox.baseVal
  if (vb.width > 0 && vb.height > 0) {
    return { x: vb.x, y: vb.y, w: vb.width, h: vb.height }
  }
  return null
}

/** 读取 Mermaid 官方 viewBox（与聊天区渲染一致，不用 node getBBox 并集） */
export function measureSvgBounds(svg: SVGSVGElement): ViewBox {
  const declared = readDeclaredViewBox(svg)
  if (declared) return declared

  try {
    const b = svg.getBBox()
    if (b.width > 0.5 && b.height > 0.5) {
      return { x: b.x, y: b.y, w: b.width, h: b.height }
    }
  } catch {
    /* ignore */
  }

  return { x: 0, y: 0, w: 800, h: 600 }
}

function prepareSvg(svg: SVGSVGElement, home: ViewBox): void {
  svg.removeAttribute('style')
  svg.style.cssText = ''
  svg.removeAttribute('width')
  svg.removeAttribute('height')
  svg.setAttribute('viewBox', `${home.x} ${home.y} ${home.w} ${home.h}`)
  svg.setAttribute('preserveAspectRatio', 'xMidYMid meet')
}

export function initMermaidOverlayViewer(
  viewport: HTMLElement,
  chartHtml: string,
  header: HTMLElement,
): MermaidOverlayViewer {
  const content = document.createElement('div')
  content.className = 'smd-mermaid-overlay-content'
  content.innerHTML = chartHtml

  const svg = content.querySelector('svg') as SVGSVGElement | null
  if (!svg) {
    viewport.textContent = '无法加载图表'
    return {
      destroy: () => {},
      zoomIn: () => {},
      zoomOut: () => {},
      reset: () => {},
    }
  }

  const homeBase = measureSvgBounds(svg)
  prepareSvg(svg, homeBase)
  viewport.appendChild(content)

  let zoom = 1
  let panX = 0
  let panY = 0
  let fitW = 0
  let fitH = 0

  const toolbar = document.createElement('div')
  toolbar.className = 'smd-mermaid-overlay-toolbar'

  const zoomOutBtn = createToolButton('smd-toolbtn smd-mermaid-overlay-tool', 'zoomOut', '缩小')
  const zoomLabel = document.createElement('span')
  zoomLabel.className = 'smd-mermaid-overlay-zoom'
  zoomLabel.textContent = '100%'
  const zoomInBtn = createToolButton('smd-toolbtn smd-mermaid-overlay-tool', 'zoomIn', '放大')
  const resetBtn = createToolButton('smd-toolbtn smd-mermaid-overlay-tool', 'reset', '复原')
  toolbar.append(zoomOutBtn, zoomLabel, zoomInBtn, resetBtn)
  header.insertBefore(toolbar, header.lastElementChild)

  const updateZoomLabel = () => {
    zoomLabel.textContent = `${Math.round(zoom * 100)}%`
  }

  const computeFit = () => {
    const vp = viewport.getBoundingClientRect()
    if (vp.width < 1 || vp.height < 1) return false

    const availW = vp.width - VIEWPORT_PAD * 2
    const availH = vp.height - VIEWPORT_PAD * 2
    const aspect = homeBase.w / homeBase.h

    if (availW / availH > aspect) {
      fitH = availH
      fitW = availH * aspect
    } else {
      fitW = availW
      fitH = availW / aspect
    }
    return true
  }

  const apply = () => {
    if (!computeFit()) return

    const displayW = fitW * zoom
    const displayH = fitH * zoom

    svg.setAttribute('width', String(Math.round(displayW)))
    svg.setAttribute('height', String(Math.round(displayH)))
    svg.setAttribute('viewBox', `${homeBase.x} ${homeBase.y} ${homeBase.w} ${homeBase.h}`)

    const vp = viewport.getBoundingClientRect()
    const left = (vp.width - displayW) / 2 + panX
    const top = (vp.height - displayH) / 2 + panY
    content.style.transform = `translate(${Math.round(left)}px, ${Math.round(top)}px)`
    updateZoomLabel()
  }

  const reset = () => {
    if (!computeFit()) {
      requestAnimationFrame(reset)
      return
    }
    zoom = 1
    panX = 0
    panY = 0
    apply()
  }

  const zoomBy = (factor: number, clientX?: number, clientY?: number) => {
    if (!computeFit()) return

    const vp = viewport.getBoundingClientRect()
    const cx = clientX ?? vp.left + vp.width / 2
    const cy = clientY ?? vp.top + vp.height / 2

    const oldZoom = zoom
    const nextZoom = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM)
    if (Math.abs(nextZoom - oldZoom) < 0.001) return

    const oldW = fitW * oldZoom
    const oldH = fitH * oldZoom
    const newW = fitW * nextZoom
    const newH = fitH * nextZoom

    const oldLeft = (vp.width - oldW) / 2 + panX
    const oldTop = (vp.height - oldH) / 2 + panY
    const relX = (cx - vp.left - oldLeft) / oldW
    const relY = (cy - vp.top - oldTop) / oldH

    const newLeft = cx - vp.left - relX * newW
    const newTop = cy - vp.top - relY * newH
    panX = newLeft - (vp.width - newW) / 2
    panY = newTop - (vp.height - newH) / 2
    zoom = nextZoom
    apply()
  }

  const zoomIn = () => zoomBy(MERMAID_ZOOM_STEP)
  const zoomOut = () => zoomBy(1 / MERMAID_ZOOM_STEP)

  let dragging = false
  let dragStartX = 0
  let dragStartY = 0
  let dragPanX = 0
  let dragPanY = 0

  const onMouseDown = (e: MouseEvent) => {
    if (e.button !== 0) return
    dragging = true
    dragStartX = e.clientX
    dragStartY = e.clientY
    dragPanX = panX
    dragPanY = panY
    viewport.classList.add('is-dragging')
    e.preventDefault()
  }

  const onMouseMove = (e: MouseEvent) => {
    if (!dragging) return
    panX = dragPanX + (e.clientX - dragStartX)
    panY = dragPanY + (e.clientY - dragStartY)
    apply()
  }

  const onMouseUp = () => {
    if (!dragging) return
    dragging = false
    viewport.classList.remove('is-dragging')
  }

  const onWheel = (e: WheelEvent) => {
    e.preventDefault()
    const factor = e.deltaY < 0 ? MERMAID_ZOOM_STEP : 1 / MERMAID_ZOOM_STEP
    zoomBy(factor, e.clientX, e.clientY)
  }

  const onResize = () => apply()

  zoomOutBtn.onclick = (e) => { e.stopPropagation(); zoomOut() }
  zoomInBtn.onclick = (e) => { e.stopPropagation(); zoomIn() }
  resetBtn.onclick = (e) => { e.stopPropagation(); reset() }

  viewport.addEventListener('mousedown', onMouseDown)
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
  viewport.addEventListener('wheel', onWheel, { passive: false })
  window.addEventListener('resize', onResize)

  requestAnimationFrame(() => requestAnimationFrame(reset))

  return {
    destroy: () => {
      viewport.removeEventListener('mousedown', onMouseDown)
      window.removeEventListener('mousemove', onMouseMove)
      window.removeEventListener('mouseup', onMouseUp)
      viewport.removeEventListener('wheel', onWheel)
      window.removeEventListener('resize', onResize)
      toolbar.remove()
      content.remove()
    },
    zoomIn,
    zoomOut,
    reset,
  }
}
