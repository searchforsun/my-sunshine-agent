import type { TenantId } from '../tenants'
import { ApiError, parseApiResponse, resolveApiMessage } from '../apiError'
import { adminHeaders, ragApiBase } from './client'
import type {
  ConfigBundleDraftView,
  ConfigSchemaResponse,
  ConfigSuggestionItem,
  ConfigVersionSummary,
  PublishBundleResult,
  PublishGateFailure,
  SubmitEvalResult,
} from './kbConfigTypes'

export * from './kbConfigTypes'

export async function fetchKbConfigSchema(tenantId: TenantId, kbId: string): Promise<ConfigSchemaResponse> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/schema`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse(res)
}

export async function fetchKbConfigDraft(tenantId: TenantId, kbId: string): Promise<ConfigBundleDraftView> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/draft`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse(res)
}

export async function saveKbConfigDraft(
  tenantId: TenantId,
  kbId: string,
  payload: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/draft`, {
    method: 'PUT',
    headers: adminHeaders(tenantId),
    body: JSON.stringify(payload),
  })
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function publishKbConfigBundle(tenantId: TenantId, kbId: string): Promise<SubmitEvalResult> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/publish`, {
    method: 'POST',
    headers: adminHeaders(tenantId),
  })
  const raw = (await res.json()) as { code: number; msg: string; data?: SubmitEvalResult }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    throw new ApiError(resolveApiMessage(raw.code, raw.msg, '提交评测失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as SubmitEvalResult
}

export async function listKbConfigVersions(tenantId: TenantId, kbId: string): Promise<ConfigVersionSummary[]> {
  const res = await fetch(`${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions`, {
    headers: adminHeaders(tenantId),
  })
  return parseApiResponse(res)
}

export async function activateKbConfigVersion(
  tenantId: TenantId,
  kbId: string,
  versionId: number,
): Promise<PublishBundleResult> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions/${versionId}/activate`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  const raw = (await res.json()) as {
    code: number
    msg: string
    data?: PublishBundleResult | PublishGateFailure
  }
  if (raw.code === 422 && raw.data) {
    throw new PublishGateError(raw.data as PublishGateFailure, raw.msg)
  }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    throw new ApiError(resolveApiMessage(raw.code, raw.msg, '生效失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as PublishBundleResult
}

export async function fetchKbConfigEffective(
  tenantId: TenantId,
  kbId: string,
  mode: 'published' | 'draft' | 'version',
  versionId?: number,
): Promise<Record<string, unknown>> {
  const qs = new URLSearchParams({ mode })
  if (versionId != null) {
    qs.set('versionId', String(versionId))
  }
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/effective?${qs}`,
    { headers: adminHeaders(tenantId) },
  )
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function forkKbConfigVersion(
  tenantId: TenantId,
  kbId: string,
  versionId: number,
): Promise<Record<string, unknown>> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions/${versionId}/fork`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  const raw = (await res.json()) as { code: number; msg: string; data?: Record<string, unknown> }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    throw new ApiError(resolveApiMessage(raw.code, raw.msg, '复制草稿失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as Record<string, unknown>
}

export async function revertKbConfigVersionToDraft(
  tenantId: TenantId,
  kbId: string,
  versionId: number,
): Promise<Record<string, unknown>> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/versions/${versionId}/revert-to-draft`,
    { method: 'POST', headers: adminHeaders(tenantId) },
  )
  const raw = (await res.json()) as { code: number; msg: string; data?: Record<string, unknown> }
  if (raw.code === 409) {
    throw new ConfigVersionConflictError(raw.msg || '版本冲突')
  }
  if (!res.ok || raw.code !== 200) {
    throw new ApiError(resolveApiMessage(raw.code, raw.msg, '转为草稿失败'), {
      kind: 'biz',
      code: raw.code,
      httpStatus: res.status,
    })
  }
  return raw.data as Record<string, unknown>
}

export async function applyConfigSuggestions(
  tenantId: TenantId,
  kbId: string,
  suggestions: ConfigSuggestionItem[],
  versionId?: number | null,
): Promise<Record<string, unknown>> {
  const res = await fetch(
    `${ragApiBase()}/api/rag/admin/kbs/${encodeURIComponent(kbId)}/config/draft/apply-suggestions`,
    {
      method: 'POST',
      headers: adminHeaders(tenantId),
      body: JSON.stringify({ suggestions, versionId: versionId ?? undefined }),
    },
  )
  return parseApiResponse<Record<string, unknown>>(res)
}

export class PublishGateError extends Error {
  readonly gate: PublishGateFailure

  constructor(gate: PublishGateFailure, message = 'publish gate failed') {
    super(message)
    this.name = 'PublishGateError'
    this.gate = gate
  }
}

export class ConfigVersionConflictError extends Error {
  readonly code: number

  constructor(message: string, code = 409) {
    super(message)
    this.name = 'ConfigVersionConflictError'
    this.code = code
  }
}
