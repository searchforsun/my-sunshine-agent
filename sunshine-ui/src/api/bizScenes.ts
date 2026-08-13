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

/** 业务场景 Lab active 码闭集（Skill/Agent 表单 biz_scene 下拉） */
export async function listActiveBizSceneCodes(): Promise<string[]> {
  const res = await fetch(apiUrl('/api/biz-scenes/active-codes'), { headers: apiHeaders() })
  return parseApiResponse<string[]>(res)
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
  body: { displayName?: string; description?: string; status?: string },
): Promise<BizSceneEntry> {
  const res = await fetch(apiUrl(`/api/biz-scenes/${encodeURIComponent(code)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<BizSceneEntry>(res)
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
