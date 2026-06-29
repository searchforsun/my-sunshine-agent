/**
 * Mermaid 初始化与 DOM 清理 — 避免语法错误时炸弹 SVG 泄漏到 body
 * 节点填充与页面正文 (--sun-black) 一致，仅保留边框线
 */
import mermaid from 'mermaid'

let lastTheme: string | null = null

export function getMermaidTheme() {
  const isLight = document.documentElement.getAttribute('data-theme') === 'light'
  const bg = isLight ? '#ffffff' : '#212121'
  const border = isLight ? '#c8c8c8' : '#525252'
  const text = isLight ? '#0d0d0d' : '#ececec'
  const line = isLight ? '#525252' : '#b4b4b4'
  return {
    // themeVariables 仅 base 主题全量生效
    theme: 'base',
    themeVariables: {
      darkMode: !isLight,
      background: 'transparent',
      fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, PingFang SC, Segoe UI, sans-serif',
      fontSize: '14px',
      primaryColor: bg,
      primaryTextColor: text,
      primaryBorderColor: border,
      secondaryColor: bg,
      secondaryTextColor: text,
      secondaryBorderColor: border,
      tertiaryColor: bg,
      tertiaryTextColor: text,
      tertiaryBorderColor: border,
      mainBkg: bg,
      nodeBorder: border,
      clusterBkg: bg,
      clusterBorder: border,
      lineColor: line,
      defaultLinkColor: line,
      titleColor: text,
      textColor: text,
      nodeTextColor: text,
      edgeLabelBackground: bg,
    },
  } as const
}

/** 语法错误时不渲染 error 图到 body，由调用方在容器内展示 */
export function ensureMermaidInitialized(): void {
  const currentTheme = document.documentElement.getAttribute('data-theme')
  if (lastTheme === currentTheme) return
  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'loose',
    suppressErrorRendering: true,
    ...getMermaidTheme(),
  })
  lastTheme = currentTheme
}

export function resetMermaidInitCache(): void {
  lastTheme = null
}

/** mermaid.render 失败时可能残留的临时节点（d{id} / svg-{id} / iframe） */
export function cleanupMermaidDom(chartId: string): void {
  for (const id of [chartId, `d${chartId}`, `i${chartId}`]) {
    document.getElementById(id)?.remove()
  }
}

export function formatMermaidError(err: unknown): string {
  if (err && typeof err === 'object' && 'str' in err && typeof (err as { str: unknown }).str === 'string') {
    return (err as { str: string }).str
  }
  if (err instanceof Error) return err.message
  return String(err)
}

export async function renderMermaidSvg(chartId: string, source: string): Promise<string> {
  ensureMermaidInitialized()
  try {
    const { svg } = await mermaid.render(chartId, source.trim())
    return svg
  } catch (err) {
    throw err
  } finally {
    cleanupMermaidDom(chartId)
  }
}
