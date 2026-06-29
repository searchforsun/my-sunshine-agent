/**
 * 全局工具栏事件 — 事件委托，避免 inline onclick 失效
 */
import { copyText } from './clipboard'
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

function setMermaidChartMode(toggleBtn: HTMLElement, zoomBtn: HTMLElement): void {
  setToolIcon(toggleBtn, 'source')
  toggleBtn.title = '源码'
  setToolIcon(zoomBtn, 'zoom')
  zoomBtn.title = '全屏'
  zoomBtn.style.display = ''
}

function setMermaidSourceMode(toggleBtn: HTMLElement, zoomBtn: HTMLElement): void {
  setToolIcon(toggleBtn, 'chart')
  toggleBtn.title = '图表'
  setToolIcon(zoomBtn, 'copy')
  zoomBtn.title = '复制'
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

function copyCodeBlock(btn: HTMLElement): void {
  const pre = btn.closest('pre')
  const code = pre?.querySelector('code')
  const text = code?.textContent || pre?.textContent || ''
  void copyText(text).then((ok) => { if (ok) flashCopied(btn) })
}

function copyMermaidSource(btn: HTMLElement): void {
  const wrap = btn.closest(`.${PREFIX}mermaid-wrapper`) as HTMLElement | null
  if (!wrap) return
  const srcPre = wrap.querySelector(`.${CP('mermaid-source')}`) as HTMLElement | null
  const text = srcPre?.textContent?.trim() || wrap.dataset.mermaidSource?.trim() || ''
  void copyText(text).then((ok) => { if (ok) flashCopied(btn) })
}

function toggleMermaid(btn: HTMLElement): void {
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
    if (zoomBtn) setMermaidSourceMode(btn, zoomBtn)
  }
}

function zoomMermaid(btn: HTMLElement): void {
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

function handleSmdToolClick(e: MouseEvent): void {
  const btn = (e.target as HTMLElement | null)?.closest('button.smd-toolbtn') as HTMLButtonElement | null
  if (!btn) return

  if (btn.classList.contains(`${CP('toolbtn-copy')}`)) {
    e.preventDefault()
    e.stopPropagation()
    copyCodeBlock(btn)
    return
  }

  if (btn.classList.contains('smd-toolbtn-toggle')) {
    e.preventDefault()
    e.stopPropagation()
    toggleMermaid(btn)
    return
  }

  if (btn.classList.contains('smd-toolbtn-zoom')) {
    e.preventDefault()
    e.stopPropagation()
    const wrap = btn.closest(`.${PREFIX}mermaid-wrapper`)
    if (wrap?.querySelector(`.${CP('mermaid-source')}`)) {
      copyMermaidSource(btn)
    } else {
      zoomMermaid(btn)
    }
  }
}

export function registerGlobalHandlers(): void {
  if (registered) return
  registered = true
  document.addEventListener('click', handleSmdToolClick, true)

  ;(window as any).__smd_copyCode = (btn: HTMLElement) => copyCodeBlock(btn)
  ;(window as any).__smd_mermaidToggle = (btn: HTMLElement) => toggleMermaid(btn)
  ;(window as any).__smd_mermaidZoom = (btn: HTMLElement) => zoomMermaid(btn)
}

/** 创建 Mermaid 工具栏：源码切换 + 全屏（源码态右侧钮变为复制） */
export function createMermaidToolButtons(): HTMLDivElement {
  const tools = document.createElement('div')
  tools.className = CP('toolbtns')
  tools.appendChild(createToolButton(
    `${CP('toolbtn')} ${CP('toolbtn-toggle')} smd-toolbtn-toggle`,
    'source',
    '源码',
  ))
  tools.appendChild(createToolButton(
    `${CP('toolbtn')} ${CP('toolbtn-zoom')} smd-toolbtn-zoom`,
    'zoom',
    '全屏',
  ))
  return tools
}
