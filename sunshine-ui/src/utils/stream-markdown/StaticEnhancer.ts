/**
 * 静态 Markdown DOM 增强器 — 为 markdown-it 渲染的静态内容补充代码头/Mermaid
 */
import { MermaidRenderer, resetMermaidSvgCacheForTheme } from './MermaidRenderer'
import { DEFAULT_CONFIG } from './types'
import { ensureMermaidInitialized, resetMermaidInitCache } from './mermaidConfig'
import { getCachedMermaidSvg } from './mermaidSvgCache'

// 确保全局 onclick 处理器已注册
import { registerGlobalHandlers, createMermaidToolButtons } from './globalHandlers'
import { createToolButton } from './toolIcons'

registerGlobalHandlers()

const CP = (s: string) => `${DEFAULT_CONFIG.classPrefix}${s}`

// 共享实例，保证 mermaid 图表 ID 全局唯一
const sharedMermaidRenderer = new MermaidRenderer()

/** 为静态渲染的 .msg-md 容器补充代码块头部和 Mermaid 渲染 */
export function enhanceStaticMarkdown(
  container: HTMLElement,
  options?: { deferMermaid?: boolean; source?: string },
): void {
  void options
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
    } else {
      enhanceCodeBlock(pre, lang, raw)
    }
  }

  renderPendingStaticMermaids(container)
}

function enhanceCodeBlock(pre: HTMLElement, lang: string, _raw: string): void {
  const head = document.createElement('div')
  head.className = CP('code-header')
  const label = document.createElement('span')
  label.className = CP('code-lang')
  label.textContent = lang || 'code'
  const tools = document.createElement('div')
  tools.className = CP('toolbtns')
  tools.appendChild(createToolButton(
    `${CP('toolbtn')} ${CP('toolbtn-copy')}`,
    'copy',
    '复制',
  ))
  head.append(label, tools)
  pre.insertBefore(head, pre.firstChild)
}

function enhanceMermaidBlock(pre: HTMLElement, source: string): void {
  const trimmed = source.trim()
  const wrap = document.createElement('div')
  wrap.className = CP('mermaid-wrapper')
  wrap.dataset.mermaidSource = trimmed

  const head = document.createElement('div')
  head.className = CP('mermaid-header')
  const label = document.createElement('span')
  label.className = CP('mermaid-label')
  label.textContent = 'mermaid'
  head.appendChild(label)
  wrap.appendChild(head)

  const cached = getCachedMermaidSvg(trimmed)
  if (cached) {
    wrap.appendChild(sharedMermaidRenderer.createChartEl(cached))
    head.appendChild(createMermaidToolButtons())
    pre.parentNode?.replaceChild(wrap, pre)
    return
  }

  const { el: placeholder } = sharedMermaidRenderer.createPlaceholder()
  wrap.appendChild(placeholder)
  pre.parentNode?.replaceChild(wrap, pre)
  renderMermaidChart(wrap, head, placeholder)
}

function renderPendingStaticMermaids(container: HTMLElement): void {
  const wraps = container.querySelectorAll(`.${CP('mermaid-wrapper')}`)
  for (const wrap of wraps) {
    const el = wrap as HTMLElement
    const placeholder = el.querySelector(`.${CP('mermaid-loading')}`) as HTMLElement | null
    if (!placeholder) continue
    const head = el.querySelector(`.${CP('mermaid-header')}`) as HTMLElement | null
    if (!head) continue
    renderMermaidChart(el, head, placeholder)
  }
}

function renderMermaidChart(wrap: HTMLElement, head: HTMLElement, placeholder: HTMLElement): void {
  const source = wrap.dataset.mermaidSource || ''
  if (!source.trim()) return
  void sharedMermaidRenderer.mount(placeholder, source).then((ok) => {
    if (ok && wrap.isConnected && !head.querySelector(`.${CP('toolbtn-toggle')}`)) {
      head.appendChild(createMermaidToolButtons())
    }
  })
}

/** 主题切换：清缓存 → 按新主题重绘 → 写回缓存 */
export async function reRenderStaticMermaids(): Promise<void> {
  resetMermaidSvgCacheForTheme()
  resetMermaidInitCache()
  ensureMermaidInitialized()

  const tasks: Promise<boolean>[] = []
  const wrappers = document.querySelectorAll(`.${DEFAULT_CONFIG.classPrefix}mermaid-wrapper`)
  for (const wrap of wrappers) {
    const el = wrap as HTMLElement
    const source = el.dataset.mermaidSource?.trim()
    if (!source) continue

    const chart = el.querySelector(`.${CP('mermaid-chart')}`) as HTMLElement | null
    const error = el.querySelector(`.${CP('mermaid-error')}`) as HTMLElement | null
    const body = chart || error
    if (!body) continue

    tasks.push(sharedMermaidRenderer.renderInto(body, source))
  }
  await Promise.all(tasks)
}
