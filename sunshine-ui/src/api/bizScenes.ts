import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface BizSceneEntry {
  bizScene: string
  displayName: string
  description?: string | null
  status: string
  tenantId?: string
  source?: string
  sourceConversationId?: string | null
  approvedBy?: string | null
  approvedAt?: string | null
  updatedAt?: string | null
}

export interface BizScenePolicyEntry {
  policyId: number
  tenantId?: string
  bizScene: string
  version: number
  status?: string
  rulesJson?: string
  effectiveFrom?: string | null
  effectiveTo?: string | null
  updatedAt?: string | null
}

export async function listBizScenes(): Promise<BizSceneEntry[]> {
  const res = await fetch(apiUrl('/api/biz-scenes'), { headers: apiHeaders() })
  return parseApiResponse<BizSceneEntry[]>(res)
}

export async function createBizScene(body: {
  bizScene: string
  displayName: string
  description?: string
}): Promise<BizSceneEntry> {
  const res = await fetch(apiUrl('/api/biz-scenes'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<BizSceneEntry>(res)
}

export async function updateBizScene(
  code: string,
  body: { displayName?: string; description?: string; status?: string; approvedBy?: string },
): Promise<BizSceneEntry> {
  const res = await fetch(apiUrl(`/api/biz-scenes/${encodeURIComponent(code)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<BizSceneEntry>(res)
}

/** auto 场景审核：approve → active（记录审核人）；reject → rejected（authority §2.1c）。 */
export async function reviewBizScene(code: string, approve: boolean, operator: string): Promise<BizSceneEntry> {
  return updateBizScene(code, approve ? { status: 'active', approvedBy: operator } : { status: 'rejected' })
}

export async function deleteBizScene(code: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/biz-scenes/${encodeURIComponent(code)}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function listBizScenePolicies(tenantId = 'default'): Promise<BizScenePolicyEntry[]> {
  const res = await fetch(apiUrl(`/api/biz-scenes/policies?tenantId=${tenantId}`), { headers: apiHeaders() })
  return parseApiResponse<BizScenePolicyEntry[]>(res)
}

export async function createBizScenePolicy(
  tenantId: string,
  body: { bizScene: string; rulesJson: string },
): Promise<BizScenePolicyEntry> {
  const res = await fetch(apiUrl(`/api/biz-scenes/policies?tenantId=${tenantId}`), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<BizScenePolicyEntry>(res)
}

export async function deleteBizScenePolicy(policyId: number): Promise<void> {
  const res = await fetch(apiUrl(`/api/biz-scenes/policies/${policyId}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}
