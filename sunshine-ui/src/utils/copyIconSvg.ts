/** 复制图标 SVG — Chat / stream-markdown / 预览区 SSOT */
export const COPY_ICON_VIEWBOX = '0 0 24 24'

export function copyIconSvg(size = 14): string {
  return `<svg width="${size}" height="${size}" viewBox="${COPY_ICON_VIEWBOX}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`
}

export function copiedIconSvg(size = 14): string {
  return `<svg width="${size}" height="${size}" viewBox="${COPY_ICON_VIEWBOX}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polyline points="20 6 9 17 4 12"/></svg>`
}
