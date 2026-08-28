import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface UsageSummaryRow {
  model: string
  calls: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  estCost: string
}

export interface UsageDailyRow {
  statDate: string
  tenantId: string
  model: string
  callSite: string | null
  calls: number
  totalTokens: number
  promptTokens: number
  completionTokens: number
  estCost: string
}

export interface UsageRecordRow {
  id: number
  tenantId: string
  userId: string | null
  model: string
  callSite: string | null
  runId: string | null
  roundId: string | null
  stream: boolean
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimated: boolean
  requestAt: number
}

export interface TenantQuota {
  id: number
  tenantId: string
  monthTokenLimit: number
  modelWhitelist: string | null
  enabled: boolean
  remark: string | null
}

export async function listUsageSummary(params?: {
  since?: number
  until?: number
  tenantId?: string
}): Promise<UsageSummaryRow[]> {
  const qs = new URLSearchParams()
  if (params?.since) qs.set('since', String(params.since))
  if (params?.until) qs.set('until', String(params.until))
  if (params?.tenantId) qs.set('tenantId', params.tenantId)
  const res = await fetch(apiUrl(`/api/usage/summary?${qs}`), { headers: apiHeaders() })
  return parseApiResponse<UsageSummaryRow[]>(res)
}

export async function listUsageDaily(params?: {
  since?: number
  until?: number
  tenantId?: string
  model?: string
}): Promise<UsageDailyRow[]> {
  const qs = new URLSearchParams()
  if (params?.since) qs.set('since', String(params.since))
  if (params?.until) qs.set('until', String(params.until))
  if (params?.tenantId) qs.set('tenantId', params.tenantId)
  if (params?.model) qs.set('model', params.model)
  const res = await fetch(apiUrl(`/api/usage/daily?${qs}`), { headers: apiHeaders() })
  return parseApiResponse<UsageDailyRow[]>(res)
}

export async function listUsageRecords(params?: {
  since?: number
  until?: number
  model?: string
  tenantId?: string
}): Promise<UsageRecordRow[]> {
  const qs = new URLSearchParams()
  if (params?.since) qs.set('since', String(params.since))
  if (params?.until) qs.set('until', String(params.until))
  if (params?.model) qs.set('model', params.model)
  if (params?.tenantId) qs.set('tenantId', params.tenantId)
  const res = await fetch(apiUrl(`/api/usage/records?${qs}`), { headers: apiHeaders() })
  return parseApiResponse<UsageRecordRow[]>(res)
}

export async function listTenantQuotas(): Promise<TenantQuota[]> {
  const res = await fetch(apiUrl('/api/usage/quota'), { headers: apiHeaders() })
  return parseApiResponse<TenantQuota[]>(res)
}

export async function upsertTenantQuota(body: {
  tenantId: string
  monthTokenLimit: number
  modelWhitelist?: string | null
  enabled?: boolean
  remark?: string | null
}): Promise<TenantQuota> {
  const res = await fetch(apiUrl('/api/usage/quota'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<TenantQuota>(res)
}

export async function deleteTenantQuota(tenantId: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/usage/quota/${encodeURIComponent(tenantId)}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}
