/** SSE 直连 Gateway，避免 Vite proxy 缓冲流式响应 */
export const BFF_STREAM_BASE = import.meta.env.VITE_BFF_STREAM_BASE ?? 'http://localhost:8000'

/** CRUD 默认走相对路径 /api，开发时经 Vite proxy 转发 */
export const BFF_API_BASE = import.meta.env.VITE_BFF_API_BASE ?? ''
