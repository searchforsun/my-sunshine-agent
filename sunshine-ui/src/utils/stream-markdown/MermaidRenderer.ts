/**
 * Mermaid 图表渲染器 — 支持明暗主题自适应
 */
import type { RendererConfig } from './types'
import { DEFAULT_CONFIG } from './types'
import {
  formatMermaidError,
  renderMermaidSvg,
  resetMermaidInitCache,
} from './mermaidConfig'

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

  async render(id: string, content: string, placeholder: HTMLElement): Promise<boolean> {
    const chartId = `svg-${++this.chartCounter}`
    try {
      const svg = await renderMermaidSvg(chartId, content)
      const container = document.createElement('div')
      container.className = `${this.prefix}mermaid-chart`
      container.innerHTML = svg
      placeholder.parentNode?.replaceChild(container, placeholder)
      return true
    } catch (err) {
      placeholder.className = `${this.prefix}mermaid-error`
      const detail = formatMermaidError(err)
      placeholder.innerHTML = [
        '<p class="smd-mermaid-error-title">图表语法有误，请检查 Mermaid 源码</p>',
        detail ? `<p class="smd-mermaid-error-detail">${escapeHtml(detail)}</p>` : '',
        `<pre class="smd-mermaid-error-source">${escapeHtml(content)}</pre>`,
      ].join('')
      return false
    }
  }

  reset(): void {
    this.chartCounter = 0
    resetMermaidInitCache()
  }
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
