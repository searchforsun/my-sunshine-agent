/**
 * Mermaid 图表渲染器 — 支持明暗主题自适应
 */
import mermaid from 'mermaid'
import type { RendererConfig } from './types'
import { DEFAULT_CONFIG } from './types'

function getTheme() {
  const isLight = document.documentElement.getAttribute('data-theme') === 'light'
  return {
    theme: isLight ? 'neutral' : 'dark',
    themeVariables: {
      primaryColor: '#f59e0b',
      primaryTextColor: isLight ? '#0f172a' : '#e2e8f0',
      lineColor: isLight ? '#94a3b8' : '#64748b',
      secondaryColor: isLight ? '#f1f5f9' : '#1a2332',
      tertiaryColor: isLight ? '#ffffff' : '#111827',
    },
  } as const
}

export class MermaidRenderer {
  private chartCounter = 0
  private lastTheme: string | null = null
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
    // 主题变化时重新初始化
    const currentTheme = document.documentElement.getAttribute('data-theme')
    if (this.lastTheme !== currentTheme) {
      const t = getTheme()
      mermaid.initialize({ startOnLoad: false, securityLevel: 'loose', ...t })
      this.lastTheme = currentTheme
    }

    try {
      const chartId = `svg-${++this.chartCounter}`
      const { svg } = await mermaid.render(chartId, content.trim())
      const container = document.createElement('div')
      container.className = `${this.prefix}mermaid-chart`
      container.innerHTML = svg
      placeholder.parentNode?.replaceChild(container, placeholder)
      return true
    } catch {
      placeholder.className = `${this.prefix}mermaid-error`
      placeholder.innerHTML = `<p>⚠️ 图表渲染失败</p><pre>${escape(content)}</pre>`
      return false
    }
  }

  reset(): void { this.chartCounter = 0; this.lastTheme = null }
}

function escape(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
