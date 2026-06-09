/**
 * 静态 Markdown DOM 增强器 — 为 markdown-it 渲染的静态内容补充代码头/Mermaid
 */
import { MermaidRenderer } from './MermaidRenderer'
import { DEFAULT_CONFIG } from './types'

// 确保全局 onclick 处理器已注册
import { registerGlobalHandlers } from './globalHandlers'

registerGlobalHandlers()

const CP = (s: string) => `${DEFAULT_CONFIG.classPrefix}${s}`

// 共享实例，保证 mermaid 图表 ID 全局唯一
const sharedMermaidRenderer = new MermaidRenderer()

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 为静态渲染的 .msg-md 容器补充代码块头部和 Mermaid 渲染 */
export function enhanceStaticMarkdown(container: HTMLElement): void {
  const pres = container.querySelectorAll('pre')

  for (let i = pres.length - 1; i >= 0; i--) {
    const pre = pres[i] as HTMLElement
    // 跳过已处理过的
    if (pre.querySelector(`.${CP('code-header')}`)) continue
    if (pre.closest(`.${CP('mermaid-wrapper')}`)) continue

    const code = pre.querySelector('code')
    if (!code) continue

    const cls = code.className || ''
    const langMatch = cls.match(/language-(\w+)/)
    const lang = langMatch ? langMatch[1] : ''
    const raw = code.textContent || ''

    if (lang === 'mermaid') {
      enhanceMermaidBlock(pre, raw)
    } else if (lang || cls.includes('hljs')) {
      enhanceCodeBlock(pre, lang, raw)
    }
  }
}

function enhanceCodeBlock(pre: HTMLElement, lang: string, _raw: string): void {
  const head = document.createElement('div')
  head.className = CP('code-header')
  head.innerHTML = `<span>${esc(lang || 'code')}</span><span class="${CP('code-copy')}" onclick="window.__smd_copyCode(this)" title="复制">◳</span>`
  pre.insertBefore(head, pre.firstChild)
}

function enhanceMermaidBlock(pre: HTMLElement, source: string): void {
  const wrap = document.createElement('div')
  wrap.className = CP('mermaid-wrapper')
  wrap.dataset.mermaidSource = source

  const head = document.createElement('div')
  head.className = CP('mermaid-header')
  head.textContent = 'mermaid'

  const { el: placeholder } = sharedMermaidRenderer.createPlaceholder()
  wrap.appendChild(head)
  wrap.appendChild(placeholder)

  pre.parentNode?.replaceChild(wrap, pre)

  renderMermaidChart(wrap as HTMLElement, head, placeholder)
}

/** 在 wrapper 中渲染 mermaid 图表 */
function renderMermaidChart(wrap: HTMLElement, head: HTMLElement, placeholder: HTMLElement): void {
  const source = wrap.dataset.mermaidSource || ''
  sharedMermaidRenderer.render(placeholder.id, source, placeholder).then((ok) => {
    if (ok) {
      // 避免重复添加按钮
      if (!head.querySelector('.smd-toolbtn-toggle')) {
        head.innerHTML += `<span class="${CP('mermaid-toolbtns')}">
          <span class="${CP('mermaid-toolbtn')} smd-toolbtn-toggle" onclick="window.__smd_mermaidToggle(this)" title="源码 / 图表">◫</span>
          <span class="${CP('mermaid-toolbtn')} smd-toolbtn-zoom" onclick="window.__smd_mermaidZoom(this)" title="全屏">◰</span>
        </span>`
      }
    }
  })
}

/** 主题切换后重渲染所有静态 Mermaid 图表 */
export function reRenderStaticMermaids(): void {
  // 强制 mermaid 重新初始化
  sharedMermaidRenderer.reset()
  const wrappers = document.querySelectorAll(`.${DEFAULT_CONFIG.classPrefix}mermaid-wrapper`)
  for (const wrap of wrappers) {
    const el = wrap as HTMLElement
    // 移除旧图表，插入占位
    const chart = el.querySelector(`.${CP('mermaid-chart')}`)
    if (chart) chart.remove()
    const error = el.querySelector(`.${CP('mermaid-error')}`)
    if (error) error.remove()

    const { el: placeholder } = sharedMermaidRenderer.createPlaceholder()
    const header = el.querySelector(`.${CP('mermaid-header')}`)
    if (header) header.after(placeholder)
    else el.appendChild(placeholder)

    renderMermaidChart(el, header as HTMLElement, placeholder)
  }
}
