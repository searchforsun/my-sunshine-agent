/**
 * Mermaid SVG 缓存 — 围栏闭合后按源码缓存，流式 DOM 重建时同步恢复，避免转圈抖动
 */
const svgBySource = new Map<string, string>()
const inflightBySource = new Map<string, Promise<string>>()

export function getCachedMermaidSvg(source: string): string | undefined {
  return svgBySource.get(source.trim())
}

export function setCachedMermaidSvg(source: string, svg: string): void {
  const key = source.trim()
  if (!key || !svg) return
  svgBySource.set(key, svg)
}

export function clearMermaidSvgCache(): void {
  svgBySource.clear()
  inflightBySource.clear()
}

/** 同名源码共用一次 mermaid.render，结果写入缓存 */
export function loadMermaidSvg(
  source: string,
  render: (chartId: string, content: string) => Promise<string>,
  nextId: () => string,
): Promise<string> {
  const key = source.trim()
  const cached = svgBySource.get(key)
  if (cached) return Promise.resolve(cached)

  const pending = inflightBySource.get(key)
  if (pending) return pending

  const task = render(nextId(), key)
    .then((svg) => {
      svgBySource.set(key, svg)
      return svg
    })
    .finally(() => {
      inflightBySource.delete(key)
    })
  inflightBySource.set(key, task)
  return task
}
