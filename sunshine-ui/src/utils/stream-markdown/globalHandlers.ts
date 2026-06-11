/**
 * 全局 onclick 事件处理 — 在 settledHtml 及静态渲染中生效
 * 模块首次 import 时自动注册，幂等
 */
import { flashCopied, setToolIcon, createToolButton } from './toolIcons'
import { initMermaidOverlayViewer, type MermaidOverlayViewer } from './mermaidOverlayViewer'
import { renderMermaidForOverlay } from './mermaidOverlayRender'

const PREFIX = 'smd-'
const CP = (s: string) => `${PREFIX}${s}`
const OVERLAY_CLASS = 'smd-mermaid-overlay'
const BODY_LOCK_CLASS = 'smd-mermaid-overlay-open'

let registered = false
let overlayKeyHandler: ((e: KeyboardEvent) => void) | null = null
let activeViewer: MermaidOverlayViewer | null = null
let overlayGeneration = 0

function bindZoomCopy(zoomBtn: HTMLElement, srcPre: HTMLElement): void {
  zoomBtn.onclick = (e) => {
    e.stopPropagation()
    navigator.clipboard.writeText(srcPre.textContent || '').then(() => {
      flashCopied(zoomBtn)
    }).catch(() => {})
  }
}

function bindZoomFullscreen(zoomBtn: HTMLElement): void {
  zoomBtn.onclick = (e) => {
    e.stopPropagation()
    ;(window as any).__smd_mermaidZoom(zoomBtn)
  }
}

function setMermaidChartMode(toggleBtn: HTMLElement, zoomBtn: HTMLElement): void {
  setToolIcon(toggleBtn, 'source')
  toggleBtn.title = '源码'
  setToolIcon(zoomBtn, 'zoom')
  zoomBtn.title = '全屏'
  zoomBtn.style.display = ''
  bindZoomFullscreen(zoomBtn)
}

function setMermaidSourceMode(toggleBtn: HTMLElement, zoomBtn: HTMLElement, srcPre: HTMLElement): void {
  setToolIcon(toggleBtn, 'chart')
  toggleBtn.title = '图表'
  setToolIcon(zoomBtn, 'copy')
  zoomBtn.title = '复制'
  bindZoomCopy(zoomBtn, srcPre)
}

function closeMermaidOverlay(): void {
  overlayGeneration += 1
  activeViewer?.destroy()
  activeViewer = null
  document.querySelector(`.${OVERLAY_CLASS}`)?.remove()
  document.body.classList.remove(BODY_LOCK_CLASS)
  if (overlayKeyHandler) {
    document.removeEventListener('keydown', overlayKeyHandler)
    overlayKeyHandler = null
  }
}

function showOverlayLoading(body: HTMLElement): void {
  body.innerHTML = `<div class="${CP('mermaid-loading')}"><div class="${CP('loading-spinner')}"></div><p>正在加载图表…</p></div>`
}

function mountOverlayViewer(body: HTMLElement, header: HTMLElement, chartHtml: string): void {
  activeViewer?.destroy()
  body.innerHTML = ''
  activeViewer = initMermaidOverlayViewer(body, chartHtml, header)
}

function openMermaidOverlay(wrap: HTMLElement): void {
  const chart = wrap.querySelector(`.${CP('mermaid-chart')}`) as HTMLElement | null
  if (!chart?.innerHTML.trim()) return

  closeMermaidOverlay()

  const overlay = document.createElement('div')
  overlay.className = OVERLAY_CLASS
  overlay.setAttribute('role', 'dialog')
  overlay.setAttribute('aria-modal', 'true')
  overlay.setAttribute('aria-label', 'Mermaid 图表全屏')

  const panel = document.createElement('div')
  panel.className = `${OVERLAY_CLASS}-panel`

  const header = document.createElement('div')
  header.className = `${OVERLAY_CLASS}-header`

  const label = document.createElement('span')
  label.className = `${OVERLAY_CLASS}-label`
  label.textContent = 'mermaid'

  const closeBtn = createToolButton(`${CP('toolbtn')} ${OVERLAY_CLASS}-close`, 'compress', '退出全屏')
  closeBtn.onclick = (e) => {
    e.stopPropagation()
    closeMermaidOverlay()
  }

  header.append(label, closeBtn)

  const body = document.createElement('div')
  body.className = `${OVERLAY_CLASS}-body`

  panel.append(header, body)
  overlay.appendChild(panel)

  document.body.appendChild(overlay)
  document.body.classList.add(BODY_LOCK_CLASS)

  overlayKeyHandler = (e: KeyboardEvent) => {
    if (e.key === 'Escape') closeMermaidOverlay()
    if (e.key === '+' || e.key === '=') activeViewer?.zoomIn()
    if (e.key === '-') activeViewer?.zoomOut()
    if (e.key === '0') activeViewer?.reset()
  }
  document.addEventListener('keydown', overlayKeyHandler)

  const source = wrap.dataset.mermaidSource?.trim()
  const generation = ++overlayGeneration

  if (source) {
    showOverlayLoading(body)
    renderMermaidForOverlay(source)
      .then((svgHtml) => {
        if (generation !== overlayGeneration || !document.body.contains(overlay)) return
        mountOverlayViewer(body, header, svgHtml)
      })
      .catch(() => {
        if (generation !== overlayGeneration || !document.body.contains(overlay)) return
        mountOverlayViewer(body, header, chart.innerHTML)
      })
  } else {
    mountOverlayViewer(body, header, chart.innerHTML)
  }
}

export function registerGlobalHandlers(): void {
  if (registered) return
  registered = true

  ;(window as any).__smd_copyCode = function (btn: HTMLElement): void {
    const pre = btn.closest('pre')
    const code = pre?.querySelector('code')
    const text = code?.textContent || ''
    navigator.clipboard.writeText(text).then(() => {
      flashCopied(btn)
    }).catch(() => {})
  }

  ;(window as any).__smd_mermaidToggle = function (btn: HTMLElement): void {
    const wrap = btn.closest(`.${PREFIX}mermaid-wrapper`) as HTMLElement
    if (!wrap) return
    const chart = wrap.querySelector(`.${CP('mermaid-chart')}`) as HTMLElement
    const zoomBtn = wrap.querySelector('.smd-toolbtn-zoom') as HTMLElement
    let srcBox = wrap.querySelector(`.${CP('mermaid-source')}`) as HTMLElement | null

    if (srcBox) {
      srcBox.remove()
      if (chart) chart.style.display = ''
      if (zoomBtn) setMermaidChartMode(btn, zoomBtn)
    } else {
      const source = wrap.dataset.mermaidSource || ''
      const pre = document.createElement('pre')
      pre.className = CP('mermaid-source')
      pre.textContent = source
      if (chart) chart.style.display = 'none'
      const header = wrap.querySelector(`.${CP('mermaid-header')}`)
      if (header) header.after(pre)
      else wrap.appendChild(pre)
      if (zoomBtn) setMermaidSourceMode(btn, zoomBtn, pre)
    }
  }

  ;(window as any).__smd_mermaidZoom = function (btn: HTMLElement): void {
    if (document.querySelector(`.${OVERLAY_CLASS}`)) {
      closeMermaidOverlay()
      return
    }

    const wrap = btn.closest(`.${PREFIX}mermaid-wrapper`) as HTMLElement
    if (!wrap) return
    const toggleBtn = wrap.querySelector('.smd-toolbtn-toggle') as HTMLElement | null

    const srcEl = wrap.querySelector(`.${CP('mermaid-source')}`) as HTMLElement | null
    if (srcEl) {
      const chart = wrap.querySelector(`.${CP('mermaid-chart')}`) as HTMLElement
      srcEl.remove()
      if (chart) chart.style.display = ''
      if (toggleBtn) {
        toggleBtn.style.display = 'none'
        setMermaidChartMode(toggleBtn, btn)
      }
    } else if (toggleBtn) {
      toggleBtn.style.display = 'none'
    }

    openMermaidOverlay(wrap)

    const onClose = () => {
      if (toggleBtn) toggleBtn.style.display = ''
      if (toggleBtn) setMermaidChartMode(toggleBtn, btn)
    }

    const observer = new MutationObserver(() => {
      if (!document.querySelector(`.${OVERLAY_CLASS}`)) {
        onClose()
        observer.disconnect()
      }
    })
    observer.observe(document.body, { childList: true })
  }
}
