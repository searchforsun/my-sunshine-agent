/**
 * 统一 API 错误：业务文案 SSOT 在后端 R.msg；前端仅兜底网络/解析异常。
 */

export type ApiErrorKind = 'network' | 'parse' | 'http' | 'biz' | 'unknown'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly code?: number
  readonly httpStatus?: number
  readonly errorKey?: string

  constructor(
    message: string,
    options: {
      kind?: ApiErrorKind
      code?: number
      httpStatus?: number
      errorKey?: string
    } = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.kind = options.kind ?? 'unknown'
    this.code = options.code
    this.httpStatus = options.httpStatus
    this.errorKey = options.errorKey
  }
}

/** 与后端 R<T> 对齐 */
export interface ApiResult<T = unknown> {
  code: number
  msg: string
  errorKey?: string
  data: T
}

export function isApiResult(raw: unknown): raw is ApiResult {
  return typeof raw === 'object'
    && raw !== null
    && 'code' in raw
    && typeof (raw as ApiResult).code === 'number'
    && 'msg' in raw
}

/** orchestrator 会话不存在（清库 / 换账号后 localStorage 残留旧 conversationId） */
export function isConversationNotFoundError(err: unknown): boolean {
  return err instanceof ApiError
    && (err.errorKey === 'orch_conversation_not_found' || err.httpStatus === 404 && err.code === 404)
}

const PARSE_FALLBACK = '服务响应异常，请稍后重试'
const NETWORK_FALLBACK = '网络连接失败，请检查网络后重试'
const DEFAULT_FALLBACK = '操作失败，请稍后重试'

function bizApiError(result: ApiResult, httpStatus: number): ApiError {
  const msg = resolveApiMessage(result.code, result.msg)
  return new ApiError(msg, {
    kind: 'biz',
    code: result.code,
    httpStatus,
    errorKey: result.errorKey,
  })
}

/** 优先使用后端 msg；仅缺失时用通用兜底 */
export function resolveApiMessage(code: number, msg?: string, fallback = DEFAULT_FALLBACK): string {
  if (typeof msg === 'string') {
    const trimmed = msg.trim()
    if (trimmed && trimmed !== 'success') return trimmed
  }
  if (code === 401) return '未登录或登录已失效，请重新登录'
  if (code === 403) return '暂无权限执行此操作'
  if (code === 404) return '请求的内容不存在'
  if (code === 429) return '操作过于频繁，请稍后再试'
  if (code >= 500) return '系统繁忙，请稍后重试'
  if (code >= 400) return '请求参数有误'
  return fallback
}

export function apiHttpError(status: number): ApiError {
  return new ApiError(resolveApiMessage(status, undefined), { kind: 'http', httpStatus: status })
}

export async function readJsonBody(res: Response): Promise<unknown> {
  let text = ''
  try {
    text = await res.text()
  } catch {
    throw new ApiError(PARSE_FALLBACK, { kind: 'parse', httpStatus: res.status })
  }
  if (!text.trim()) {
    return null
  }
  try {
    return JSON.parse(text) as unknown
  } catch {
    throw new ApiError(PARSE_FALLBACK, { kind: 'parse', httpStatus: res.status })
  }
}

/** 非 2xx 时解析 R 体并抛出 ApiError（SSE 建连失败等） */
export async function throwIfHttpError(response: Response): Promise<void> {
  if (response.ok) return
  const raw = await readJsonBody(response).catch(() => null)
  if (isApiResult(raw) && raw.code !== 200) {
    throw bizApiError(raw, response.status)
  }
  throw apiHttpError(response.status)
}

/** SSE 建连：Sa-Token 401 等会以 HTTP 200 + text/plain JSON 返回，需提前拦截 */
export async function throwIfNotEventStream(response: Response): Promise<void> {
  const ct = response.headers.get('content-type') ?? ''
  if (ct.includes('text/event-stream')) return
  const raw = await readJsonBody(response).catch(() => null)
  if (isApiResult(raw) && raw.code !== 200) {
    throw bizApiError(raw, response.status)
  }
  if (!response.ok) {
    throw apiHttpError(response.status)
  }
  throw new ApiError(PARSE_FALLBACK, { kind: 'parse', httpStatus: response.status })
}

function emptyBodyOrThrow<T>(res: Response, allowEmptyData?: boolean): T {
  if (allowEmptyData) return null as T
  throw new ApiError(PARSE_FALLBACK, { kind: 'parse', httpStatus: res.status })
}

export interface ParseApiOptions {
  allowEmptyData?: boolean
}

function throwIfApiError(raw: ApiResult, httpStatus: number): void {
  if (raw.code !== 200) {
    throw bizApiError(raw, httpStatus)
  }
}

export async function parseApiResponse<T>(
  res: Response,
  options: ParseApiOptions = {},
): Promise<T> {
  const raw = await readJsonBody(res)

  if (raw === null) {
    if (!res.ok) throw apiHttpError(res.status)
    return emptyBodyOrThrow<T>(res, options.allowEmptyData)
  }

  if (isApiResult(raw)) {
    throwIfApiError(raw, res.status)
    if (!options.allowEmptyData && (raw.data === null || raw.data === undefined)) {
      throw new ApiError(PARSE_FALLBACK, {
        kind: 'biz',
        code: raw.code,
        httpStatus: res.status,
        errorKey: raw.errorKey,
      })
    }
    return raw.data as T
  }

  if (!res.ok) {
    throw apiHttpError(res.status)
  }

  return raw as T
}

export async function parseBffPayload<T>(
  res: Response,
  options: ParseApiOptions = {},
): Promise<T> {
  if (!res.ok) {
    const raw = await readJsonBody(res).catch(() => null)
    if (isApiResult(raw)) {
      throwIfApiError(raw, res.status)
    }
    throw apiHttpError(res.status)
  }

  const raw = await readJsonBody(res)

  if (raw === null) {
    return emptyBodyOrThrow<T>(res, options.allowEmptyData)
  }

  if (isApiResult(raw)) {
    throwIfApiError(raw, res.status)
    if (!options.allowEmptyData && (raw.data === null || raw.data === undefined)) {
      throw new ApiError(PARSE_FALLBACK, {
        kind: 'biz',
        code: raw.code,
        httpStatus: res.status,
        errorKey: raw.errorKey,
      })
    }
    return raw.data as T
  }

  return raw as T
}

function isParseLikeMessage(msg: string): boolean {
  const lower = msg.toLowerCase()
  return lower.includes('unexpected token')
    || lower.includes('not valid json')
    || /^HTTP \d{3}$/.test(msg.trim())
}

function isNetworkLike(err: unknown): boolean {
  if (!(err instanceof Error)) return false
  const msg = err.message.toLowerCase()
  return (
    msg.includes('network error')
    || msg.includes('failed to fetch')
    || msg.includes('load failed')
    || msg.includes('networkerror')
    || (err.name === 'TypeError' && msg.includes('fetch'))
  )
}

export function friendlyErrorMessage(err: unknown, fallback = DEFAULT_FALLBACK): string {
  if (err instanceof ApiError) return err.message
  if (isNetworkLike(err)) return NETWORK_FALLBACK
  if (err instanceof SyntaxError) return PARSE_FALLBACK
  if (err instanceof Error) {
    if (isParseLikeMessage(err.message)) return fallback
    return err.message
  }
  return fallback
}
