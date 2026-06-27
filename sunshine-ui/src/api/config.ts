/** SSE / CRUD 公网 Gateway 基址（生产构建须设 VITE_BFF_STREAM_BASE） */
export function resolveBffStreamBase(): string {
  const configured = import.meta.env.VITE_BFF_STREAM_BASE?.trim()
  if (configured) return configured.replace(/\/$/, '')
  // 开发态走 Vite proxy，兼容 localhost:5173 端口转发（无需再暴露 8000）
  if (import.meta.env.DEV && typeof window !== 'undefined') {
    return window.location.origin
  }
  if (typeof window !== 'undefined') {
    const host = window.location.hostname
    if (host) return `http://${host}:8000`
  }
  return 'http://localhost:8000'
}

/** CRUD 与 SSE 统一走 Gateway；VITE_BFF_API_BASE 可单独覆盖 */
export function resolveApiBase(): string {
  const apiBase = import.meta.env.VITE_BFF_API_BASE?.trim()
  if (apiBase) return apiBase.replace(/\/$/, '')
  return resolveBffStreamBase()
}

/** 状态页探测基址：与 resolveApiBase 一致，统一经 Gateway :8000（开发态经 Vite /api、/v1 代理） */
export function resolveGatewayProbeBase(): string {
  return resolveApiBase()
}

/** 状态页 /health/* 探测 URL：Vite dev/preview 走同源代理，避免跨域 :8000 被 CORS/混合内容拦截 */
export function resolveHealthProbeUrl(gatewayPath: string): string {
  if (typeof window !== 'undefined') {
    const port = window.location.port
    if (import.meta.env.DEV || port === '5173' || port === '5174') {
      return gatewayPath
    }
  }
  return `${resolveGatewayProbeBase()}${gatewayPath}`
}
