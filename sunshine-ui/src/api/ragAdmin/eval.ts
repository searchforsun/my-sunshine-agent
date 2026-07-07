import type { TenantId } from '../tenants'
import { ApiError, parseApiResponse, resolveApiMessage } from '../apiError'
import { adminHeaders, ragApiBase } from './client'
import type {
  EvalJobStatus,
  EvalJobSummary,
  EvalReportView,
  EvalSuggestResult,
  EvalSuiteCreateRequest,
  EvalSuiteDetail,
  EvalSuiteQueryRequest,
  EvalSuiteSummary,
  EvalSuiteUpdateRequest,
} from './kbConfigTypes'
import { ConfigVersionConflictError } from './kbConfig'

export async function listEvalSuites(tenantId: TenantId): Promise<EvalSuiteSummary[]> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suites`, { headers: adminHeaders(tenantId) })
  return parseApiResponse<EvalSuiteSummary[]>(res)
}

export async function getEvalSuite(tenantId: TenantId, suiteKey: string): Promise<EvalSuiteDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function createEvalSuite(
  tenantId: TenantId,
  body: EvalSuiteCreateRequest,
): Promise<EvalSuiteDetail> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suites`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(body),
  })
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function updateEvalSuite(
  tenantId: TenantId,
  suiteKey: string,
  body: EvalSuiteUpdateRequest,
): Promise<EvalSuiteDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}`,
    {
      method: 'PUT',
      headers: adminHeaders(tenantId),
      body: JSON.stringify(body),
    },
  )
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function deleteEvalSuite(tenantId: TenantId, suiteKey: string): Promise<void> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}`,
    { method: 'DELETE', headers: adminHeaders(tenantId) },
  )
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function mutateEvalSuiteQuery(
  tenantId: TenantId,
  suiteKey: string,
  body: EvalSuiteQueryRequest,
): Promise<EvalSuiteDetail> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/eval/suites/${encodeURIComponent(suiteKey)}/queries`,
    { method: 'POST', headers: adminHeaders(tenantId), body: JSON.stringify(body) },
  )
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function ensureKbCustomEvalSuite(
  tenantId: TenantId,
  kbId: string,
  displayName?: string,
): Promise<EvalSuiteDetail> {
  const qs = new URLSearchParams({ kbId })
  if (displayName) qs.set('displayName', displayName)
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suites/kb-custom/ensure?${qs}`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<EvalSuiteDetail>(res)
}

export async function listEvalJobs(tenantId: TenantId, kbId: string, limit = 20): Promise<EvalJobSummary[]> {
  const qs = new URLSearchParams({ kbId, limit: String(limit) })
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/jobs?${qs}`, { headers: adminHeaders(tenantId) })
  return parseApiResponse<EvalJobSummary[]>(res)
}

export async function suggestEvalFix(
  tenantId: TenantId,
  body: { reportId: number; kbId?: string; regenerate?: boolean },
): Promise<EvalSuggestResult> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/suggest`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(body),
  })
  return parseApiResponse<EvalSuggestResult>(res)
}

export async function runEval(
  tenantId: TenantId,
  body: {
    suiteKey?: string
    kbId?: string
    strategy?: string
    configMode?: string
    configVersionId?: number
  },
): Promise<EvalJobStatus> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/run`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(body),
  })
  const raw = (await res.json()) as { code: number; msg: string; data?: EvalJobStatus }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '当前配置正在评测中，请稍后')
  }
  if (!res.ok || raw.code !== 200) {
    throw new ApiError(resolveApiMessage(raw.code, raw.msg, '运行评测失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as EvalJobStatus
}

export async function getEvalJob(tenantId: TenantId, jobId: number): Promise<EvalJobStatus> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/jobs/${jobId}`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<EvalJobStatus>(res)
}

export async function getEvalReport(tenantId: TenantId, reportId: number): Promise<EvalReportView> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/eval/reports/${reportId}`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse<EvalReportView>(res)
}
