/**
 * 全局 onclick 事件处理 — 在 settledHtml 及静态渲染中生效
 * 模块首次 import 时自动注册，幂等
 */
const PREFIX = 'smd-'
const CP = (s: string) => `${PREFIX}${s}`

let registered = false

export function registerGlobalHandlers(): void {
  if (registered) return
  registered = true

  // 代码复制
  ;(window as any).__smd_copyCode = function (btn: HTMLElement): void {
    const pre = btn.closest('pre')
    const code = pre?.querySelector('code')
    const text = code?.textContent || ''
    navigator.clipboard.writeText(text).then(() => {
      btn.textContent = '◆'
      setTimeout(() => { btn.textContent = '◳' }, 1500)
    }).catch(() => {})
  }

  // Mermaid 源码/图切换
  ;(window as any).__smd_mermaidToggle = function (btn: HTMLElement): void {
    const wrap = btn.closest(`.${PREFIX}mermaid-wrapper`) as HTMLElement
    if (!wrap) return
    const chart = wrap.querySelector(`.${CP('mermaid-chart')}`) as HTMLElement
    const zoomBtn = wrap.querySelector('.smd-toolbtn-zoom') as HTMLElement
    let srcBox = wrap.querySelector(`.${CP('mermaid-source')}`) as HTMLElement | null

    if (srcBox) {
      srcBox.remove()
      if (chart) chart.style.display = ''
      if (zoomBtn) { zoomBtn.style.display = ''; zoomBtn.textContent = '◰'; zoomBtn.setAttribute('title', '全屏'); zoomBtn.onclick = function(e) { (window as any).__smd_mermaidZoom(zoomBtn) } }
      btn.textContent = '◫'
    } else {
      const source = wrap.dataset.mermaidSource || ''
      const pre = document.createElement('pre')
      pre.className = CP('mermaid-source')
      pre.textContent = source
      if (chart) chart.style.display = 'none'
      if (zoomBtn) { zoomBtn.textContent = '◳'; zoomBtn.setAttribute('title', '复制'); zoomBtn.onclick = function (e) { e.stopPropagation(); (window as any).__smd_copyMermaidSource(pre) } }
      const header = wrap.querySelector(`.${CP('mermaid-header')}`)
      if (header) header.after(pre); else wrap.appendChild(pre)
      btn.textContent = '◲'
    }
  }

  // Mermaid 源码复制
  ;(window as any).__smd_copyMermaidSource = function (srcPre: HTMLElement): void {
    navigator.clipboard.writeText(srcPre.textContent || '').then(() => {
      const wrap = srcPre.closest?.(`.${PREFIX}mermaid-wrapper`) as HTMLElement
      const zoomBtn = wrap?.querySelector('.smd-toolbtn-zoom') as HTMLElement
      if (zoomBtn) { zoomBtn.textContent = '◆'; setTimeout(() => { zoomBtn.textContent = '◳' }, 1500) }
    }).catch(() => {})
  }

  // Mermaid 全屏
  ;(window as any).__smd_mermaidZoom = function (btn: HTMLElement): void {
    const wrap = btn.closest(`.${PREFIX}mermaid-wrapper`) as HTMLElement
    if (!wrap) return
    const toggleBtn = wrap.querySelector('.smd-toolbtn-toggle') as HTMLElement

    if (document.fullscreenElement) {
      document.exitFullscreen()
      return
    }

    const srcEl = wrap.querySelector(`.${CP('mermaid-source')}`) as HTMLElement
    if (srcEl) {
      const chart = wrap.querySelector(`.${CP('mermaid-chart')}`) as HTMLElement
      srcEl.remove()
      if (chart) chart.style.display = ''
      if (toggleBtn) { toggleBtn.textContent = '◫'; toggleBtn.style.display = 'none' }
    } else {
      if (toggleBtn) toggleBtn.style.display = 'none'
    }

    wrap.classList.add('smd-zoomed')
    wrap.requestFullscreen().catch(() => {})

    const onFsChange = () => {
      if (!document.fullscreenElement) {
        wrap.classList.remove('smd-zoomed')
        if (toggleBtn) toggleBtn.style.display = ''
        btn.textContent = '◰'; btn.setAttribute('title', '全屏')
        document.removeEventListener('fullscreenchange', onFsChange)
      }
    }
    document.addEventListener('fullscreenchange', onFsChange)
  }
}
