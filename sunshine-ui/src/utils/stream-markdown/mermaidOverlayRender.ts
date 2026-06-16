/**
 * 全屏 overlay 专用 Mermaid 渲染（独立 ID，避免与正文冲突）
 */
import { renderMermaidSvg, resetMermaidInitCache, formatMermaidError } from './mermaidConfig'

let overlayCounter = 0

export function resetOverlayMermaidCache(): void {
  overlayCounter = 0
  resetMermaidInitCache()
}

export async function renderMermaidForOverlay(source: string): Promise<string> {
  const id = `smd-overlay-${++overlayCounter}`
  try {
    return await renderMermaidSvg(id, source)
  } catch (err) {
    throw new Error(formatMermaidError(err))
  }
}
