/**
 * 全屏 overlay 专用 Mermaid 渲染（独立 ID，避免与正文冲突）
 */
import mermaid from 'mermaid'

let overlayCounter = 0
let lastTheme: string | null = null

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

function ensureMermaidInit(): void {
  const currentTheme = document.documentElement.getAttribute('data-theme')
  if (lastTheme !== currentTheme) {
    mermaid.initialize({ startOnLoad: false, securityLevel: 'loose', ...getTheme() })
    lastTheme = currentTheme
  }
}

export async function renderMermaidForOverlay(source: string): Promise<string> {
  ensureMermaidInit()
  const id = `smd-overlay-${++overlayCounter}`
  const { svg } = await mermaid.render(id, source.trim())
  return svg
}
