/**
 * Mermaid 图表渲染器 — 支持明暗主题自适应；闭合源码走 SVG 缓存
 */
import type { RendererConfig } from './types'
import { DEFAULT_CONFIG } from './types'
import {
  formatMermaidError,
  renderMermaidSvg,
  resetMermaidInitCache,
} from './mermaidConfig'
import {
  clearMermaidSvgCache,
  getCachedMermaidSvg,
  getMermaidError,
  loadMermaidSvg,
  setCachedMermaidSvg,
  setMermaidError,
} from './mermaidSvgCache'

export class MermaidRenderer {
  private chartCounter = 0
  private config: RendererConfig
  private prefix: string

  constructor(config: Partial<RendererConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config }
    this.prefix = this.config.classPrefix
  }

  createPlaceholder(): { id: string; el: HTMLElement } {
    const id = `mermaid-${++this.chartCounter}`
    const el = document.createElement('div')
    el.id = id
    el.className = `${this.prefix}mermaid-loading`
    el.innerHTML = `<div class="${this.prefix}loading-spinner"></div><p>正在生成图表…</p>`
    return { id, el }
  }

  createChartEl(svg: string): HTMLElement {
    const container = document.createElement('div')
    container.className = `${this.prefix}mermaid-chart`
    container.innerHTML = svg
    return container
  }

  /** 有缓存则同步挂载；否则 loading → render → 缓存；已确认失败直接恢复错误态 */
  mount(placeholder: HTMLElement, content: string): Promise<boolean> {
    const trimmed = content.trim()
    if (!trimmed || !placeholder.isConnected) return Promise.resolve(false)

    const cached = getCachedMermaidSvg(trimmed)
    if (cached) {
      if (!placeholder.parentNode) return Promise.resolve(false)
      placeholder.parentNode.replaceChild(this.createChartEl(cached), placeholder)
      return Promise.resolve(true)
    }

    const cachedError = getMermaidError(trimmed)
    if (cachedError) {
      if (!placeholder.parentNode) return Promise.resolve(false)
      this.showError(placeholder, trimmed, new Error(cachedError))
      return Promise.resolve(true)
    }

    return loadMermaidSvg(trimmed, renderMermaidSvg, () => `svg-${++this.chartCounter}`)
      .then((svg) => {
        if (!placeholder.isConnected || !placeholder.parentNode) return false
        placeholder.parentNode.replaceChild(this.createChartEl(svg), placeholder)
        return true
      })
      .catch((err) => {
        if (!placeholder.isConnected) return false
        this.showError(placeholder, trimmed, err)
        return false
      })
  }

  async render(_id: string, content: string, placeholder: HTMLElement): Promise<boolean> {
    return this.mount(placeholder, content)
  }

  /** 主题切换：静默重绘并刷新缓存 */
  async renderInto(body: HTMLElement, content: string): Promise<boolean> {
    const trimmed = content.trim()
    if (!trimmed || !body.isConnected) return false
    try {
      const svg = await renderMermaidSvg(`svg-${++this.chartCounter}`, trimmed)
      if (!body.isConnected) return false
      setCachedMermaidSvg(trimmed, svg)
      body.className = `${this.prefix}mermaid-chart`
      body.innerHTML = svg
      return true
    } catch (err) {
      if (!body.isConnected) return false
      this.showError(body, trimmed, err)
      setMermaidError(trimmed, formatMermaidError(err))
      return false
    }
  }

  private showError(el: HTMLElement, source: string, err: unknown): void {
    el.className = `${this.prefix}mermaid-error`
    const detail = formatMermaidError(err)
    el.innerHTML = [
      '<p class="smd-mermaid-error-title">图表语法有误，请检查 Mermaid 源码</p>',
      detail ? `<p class="smd-mermaid-error-detail">${escapeHtml(detail)}</p>` : '',
      `<pre class="smd-mermaid-error-source">${escapeHtml(source)}</pre>`,
    ].join('')
  }

  reset(): void {
    this.chartCounter = 0
    resetMermaidInitCache()
  }
}

/** 主题切换前清空 SVG 缓存（配色已变） */
export function resetMermaidSvgCacheForTheme(): void {
  clearMermaidSvgCache()
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
