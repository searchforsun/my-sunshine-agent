import type { TenantId } from '../tenants'
import { parseApiResponse } from '../apiError'
import { adminHeaders, ragApiBase } from './client'

/** task 会话历史语义检索（scene=task / layer=body|process）命中片段 */
export interface TaskHistoryHit {
  convId: string
  msgId: string
  content: string
  score: number
  createdAt: number
}

export interface TaskHistorySearchResponse {
  results: TaskHistoryHit[]
}

/**
 * L3 任务检索：跨会话检索 task 会话的历史正文/过程段落。
 * scope=session 传 convId；scope=workspace 传 convIds（含当前会话时按后端展开）。
 */
export async function searchTaskHistory(
  tenantId: TenantId,
  body: {
    userId: string
    query: string
    convId?: string
    convIds?: string[]
    topK?: number
  },
): Promise<TaskHistoryHit[]> {
  const res = await fetch(`${ragApiBase()}/api/rag/chat-history/search`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify({
      userId: body.userId,
      tenantId,
      query: body.query,
      convId: body.convId,
      convIds: body.convIds && body.convIds.length ? body.convIds : undefined,
      scene: 'task',
      layers: ['body', 'process'],
      topK: body.topK ?? 20,
    }),
  })
  const resp = await parseApiResponse<TaskHistorySearchResponse>(res)
  return resp.results ?? []
}
