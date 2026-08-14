/**
 * Mermaid SVG 缓存 — 围栏闭合后按源码缓存，流式 DOM 重建时同步恢复，避免转圈抖动
 * 渲染失败（语法错误/加载失败）同样按源码缓存，流式重建直接恢复错误态，避免 loading→失败 反复切换导致页面跳动
 */
import { formatMermaidError } from './mermaidConfig'

const svgBySource = new Map<string, string>()
const errorBySource = new Map<string, string>()
const inflightBySource = new Map<string, Promise<string>>()

export function getCachedMermaidSvg(source: string): string | undefined {
  return svgBySource.get(source.trim())
}

export function setCachedMermaidSvg(source: string, svg: string): void {
  const key = source.trim()
  if (!key || !svg) return
  svgBySource.set(key, svg)
}

/** 已确认失败（语法错误/模块加载失败）的源码 → 格式化错误详情，流式重建直接恢复错误态 */
export function getMermaidError(source: string): string | undefined {
  return errorBySource.get(source.trim())
}

export function setMermaidError(source: string, detail: string): void {
  const key = source.trim()
  if (!key || !detail) return
  errorBySource.set(key, detail)
}

export function clearMermaidSvgCache(): void {
  svgBySource.clear()
  errorBySource.clear()
  inflightBySource.clear()
}

/** 同名源码共用一次 mermaid.render，结果写入缓存；失败写入错误缓存并抛出 */
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
      errorBySource.delete(key)
      return svg
    })
    .catch((err) => {
      errorBySource.set(key, formatMermaidError(err))
      throw err
    })
    .finally(() => {
      inflightBySource.delete(key)
    })
  inflightBySource.set(key, task)
  return task
}
