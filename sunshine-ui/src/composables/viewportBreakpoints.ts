/**
 * 视口断点常量（无依赖，供 useViewportMode / useSidebar 共用，避免循环 import）
 */
export const NARROW_VIEWPORT_MAX_WIDTH = 768
export const COMPACT_VIEWPORT_MAX_WIDTH = 1260

/** 与纯 CSS @media 断点同源的媒体查询串（仅存参考，运行时不依赖） */
export const NARROW_VIEWPORT_QUERY = `(max-width: ${NARROW_VIEWPORT_MAX_WIDTH}px)`
export const COMPACT_VIEWPORT_QUERY = `(max-width: ${COMPACT_VIEWPORT_MAX_WIDTH}px)`
