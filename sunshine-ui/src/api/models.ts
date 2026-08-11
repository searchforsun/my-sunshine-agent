import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface ModelCapabilities {
  reasoning: boolean
  multimodal: boolean
  toolCall: boolean
}

/** 模型请求缺省参数表单（字段名=上游 OpenAI 兼容原名） */
export interface ModelRequestExtrasDraft {
  reasoning_split: boolean | null
  /** thinking.type：adaptive | disabled；null=不传 */
  thinking_type: 'adaptive' | 'disabled' | null
  temperature: number | null
  top_p: number | null
  /** stream_options.include_usage */
  stream_options_include_usage: boolean | null
  service_tier: 'standard' | 'priority' | null
}

export function emptyRequestExtrasDraft(): ModelRequestExtrasDraft {
  return {
    reasoning_split: null,
    thinking_type: null,
    temperature: null,
    top_p: null,
    stream_options_include_usage: null,
    service_tier: null,
  }
}

function asFiniteNumber(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.trim() && Number.isFinite(Number(v))) return Number(v)
  return null
}

function asBool(v: unknown): boolean | null {
  if (typeof v === 'boolean') return v
  return null
}

export function parseRequestExtrasDraft(
  extras: Record<string, unknown> | null | undefined,
): ModelRequestExtrasDraft {
  const draft = emptyRequestExtrasDraft()
  if (!extras) return draft
  draft.reasoning_split = asBool(extras.reasoning_split)
  draft.temperature = asFiniteNumber(extras.temperature)
  draft.top_p = asFiniteNumber(extras.top_p)
  const tier = extras.service_tier
  if (tier === 'standard' || tier === 'priority') draft.service_tier = tier
  const thinking = extras.thinking
  if (thinking && typeof thinking === 'object' && !Array.isArray(thinking)) {
    const t = (thinking as Record<string, unknown>).type
    if (t === 'adaptive' || t === 'disabled') draft.thinking_type = t
  }
  const streamOptions = extras.stream_options
  if (streamOptions && typeof streamOptions === 'object' && !Array.isArray(streamOptions)) {
    draft.stream_options_include_usage = asBool(
      (streamOptions as Record<string, unknown>).include_usage,
    )
  }
  return draft
}

/** 从 extras / 定义列解析输出上限（SSOT = max_completion_tokens） */
export function resolveMaxCompletionTokens(
  extras: Record<string, unknown> | null | undefined,
  fallbackMaxOutputTokens?: number | null,
): number {
  const fromExtras = asFiniteNumber(extras?.max_completion_tokens)
  if (fromExtras != null && fromExtras > 0) return fromExtras
  if (fallbackMaxOutputTokens != null && fallbackMaxOutputTokens > 0) return fallbackMaxOutputTokens
  return 8192
}

/**
 * 仅输出已填写项；max_completion_tokens 由输出上限写入。
 * 废弃字段 max_tokens 不再写出。
 */
export function buildRequestExtras(
  draft: ModelRequestExtrasDraft,
  maxCompletionTokens?: number | null,
): Record<string, unknown> | null {
  const out: Record<string, unknown> = {}
  if (draft.reasoning_split != null) out.reasoning_split = draft.reasoning_split
  if (draft.thinking_type != null) out.thinking = { type: draft.thinking_type }
  if (draft.temperature != null) out.temperature = draft.temperature
  if (draft.top_p != null) out.top_p = draft.top_p
  if (maxCompletionTokens != null && maxCompletionTokens > 0) {
    out.max_completion_tokens = maxCompletionTokens
  }
  if (draft.stream_options_include_usage != null) {
    out.stream_options = { include_usage: draft.stream_options_include_usage }
  }
  if (draft.service_tier != null) out.service_tier = draft.service_tier
  return Object.keys(out).length ? out : null
}

export interface ModelProvider {
  id: number
  providerKey: string
  displayName: string
  protocol: string
  baseUrl: string
  pathPrefix: string
  enabled: boolean
  tenantId: string
  configured: boolean
  apiKeyMasked: string
  createdAt?: string
  updatedAt?: string
}

export interface ModelProviderWrite {
  providerKey?: string
  displayName: string
  protocol?: string
  baseUrl: string
  pathPrefix?: string
  apiKey?: string
  enabled?: boolean
  tenantId?: string
}

export interface ModelDefinition {
  id: number
  providerKey: string
  modelName: string
  displayName: string
  contextWindow: number
  maxOutputTokens?: number
  encoding: string
  capabilities: ModelCapabilities
  /** OpenAI 兼容请求缺省参数（reasoning_split / temperature 等） */
  requestExtras?: Record<string, unknown> | null
  userSelectable: boolean
  enabled: boolean
  sortOrder: number
  tenantId: string
  createdAt?: string
  updatedAt?: string
}

export interface ModelDefinitionWrite {
  providerKey: string
  modelName: string
  displayName: string
  contextWindow?: number
  maxOutputTokens?: number
  encoding?: string
  capabilities?: ModelCapabilities
  requestExtras?: Record<string, unknown> | null
  userSelectable?: boolean
  enabled?: boolean
  sortOrder?: number
  tenantId?: string
}

export interface ModelScene {
  id: number
  sceneKey: string
  label: string
  description: string
  primaryModel: string
  fallbackModel: string | null
  extras: Record<string, unknown> | null
  enabled: boolean
  tenantId: string
  remark: string | null
  createdAt?: string
  updatedAt?: string
}

export interface ModelSceneKeyMeta {
  sceneKey: string
  label: string
  description: string
}

export interface ModelSceneUpsert {
  sceneKey: string
  primaryModel: string
  fallbackModel?: string | null
  extras?: Record<string, unknown> | null
  enabled?: boolean
  tenantId?: string
  remark?: string | null
}

export interface ModelCatalogProvider {
  providerKey: string
  displayName: string
  protocol: string
  baseUrl: string
  pathPrefix: string
  enabled: boolean
}

export interface ModelCatalogDefinition {
  modelName: string
  providerKey: string
  displayName: string
  contextWindow: number
  maxOutputTokens?: number
  encoding: string
  capabilities: ModelCapabilities
  userSelectable: boolean
  enabled: boolean
  sortOrder: number
}

export interface ModelCatalogScene {
  sceneKey: string
  primaryModel: string
  fallbackModel: string | null
  extras: Record<string, unknown> | null
  enabled: boolean
}

export interface ModelCatalog {
  providers: ModelCatalogProvider[]
  definitions: ModelCatalogDefinition[]
  scenes: ModelCatalogScene[]
}

export function emptyCapabilities(): ModelCapabilities {
  return { reasoning: false, multimodal: false, toolCall: true }
}

export function normalizeCapabilities(raw: unknown): ModelCapabilities {
  if (!raw || typeof raw !== 'object') return emptyCapabilities()
  const o = raw as Record<string, unknown>
  return {
    reasoning: Boolean(o.reasoning),
    multimodal: Boolean(o.multimodal),
    toolCall: Boolean(o.toolCall ?? o.tool_call ?? true),
  }
}

/** 下拉：全部已启用定义（管理页 / Agents / KB） */
export function catalogEnabledModelOptions(catalog: ModelCatalog) {
  return [...catalog.definitions]
    .filter((d) => d.enabled)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.modelName.localeCompare(b.modelName))
    .map((d) => ({
      label: `${d.displayName} (${d.modelName})`,
      value: d.modelName,
      providerKey: d.providerKey,
      capabilities: normalizeCapabilities(d.capabilities),
    }))
}

/** Chat：仅 user_selectable && enabled */
export function catalogUserSelectableOptions(catalog: ModelCatalog) {
  return [...catalog.definitions]
    .filter((d) => d.enabled && d.userSelectable)
    .sort((a, b) => a.sortOrder - b.sortOrder || a.modelName.localeCompare(b.modelName))
    .map((d) => ({
      label: d.displayName || d.modelName,
      value: d.modelName,
      providerKey: d.providerKey,
      capabilities: normalizeCapabilities(d.capabilities),
    }))
}

export async function listModelProviders(tenantId?: string): Promise<ModelProvider[]> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/models/providers${qs}`), { headers: apiHeaders() })
  return parseApiResponse<ModelProvider[]>(res)
}

export async function createModelProvider(body: ModelProviderWrite): Promise<ModelProvider> {
  const res = await fetch(apiUrl('/api/models/providers'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<ModelProvider>(res)
}

export async function updateModelProvider(id: number, body: ModelProviderWrite): Promise<ModelProvider> {
  const res = await fetch(apiUrl(`/api/models/providers/${id}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<ModelProvider>(res)
}

export async function deleteModelProvider(id: number): Promise<void> {
  const res = await fetch(apiUrl(`/api/models/providers/${id}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}

export async function listModelDefinitions(tenantId?: string): Promise<ModelDefinition[]> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/models/definitions${qs}`), { headers: apiHeaders() })
  const list = await parseApiResponse<ModelDefinition[]>(res)
  return list.map((d) => ({ ...d, capabilities: normalizeCapabilities(d.capabilities) }))
}

export async function createModelDefinition(body: ModelDefinitionWrite): Promise<ModelDefinition> {
  const res = await fetch(apiUrl('/api/models/definitions'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const d = await parseApiResponse<ModelDefinition>(res)
  return { ...d, capabilities: normalizeCapabilities(d.capabilities) }
}

export async function updateModelDefinition(id: number, body: ModelDefinitionWrite): Promise<ModelDefinition> {
  const res = await fetch(apiUrl(`/api/models/definitions/${id}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const d = await parseApiResponse<ModelDefinition>(res)
  return { ...d, capabilities: normalizeCapabilities(d.capabilities) }
}

export async function toggleModelDefinition(id: number): Promise<ModelDefinition> {
  const res = await fetch(apiUrl(`/api/models/definitions/${id}/toggle`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  const d = await parseApiResponse<ModelDefinition>(res)
  return { ...d, capabilities: normalizeCapabilities(d.capabilities) }
}

export async function deleteModelDefinition(id: number): Promise<void> {
  const res = await fetch(apiUrl(`/api/models/definitions/${id}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}

export async function listModelSceneKeys(): Promise<ModelSceneKeyMeta[]> {
  const res = await fetch(apiUrl('/api/models/scenes/keys'), { headers: apiHeaders() })
  return parseApiResponse<ModelSceneKeyMeta[]>(res)
}

export async function listModelScenes(tenantId?: string): Promise<ModelScene[]> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/models/scenes${qs}`), { headers: apiHeaders() })
  return parseApiResponse<ModelScene[]>(res)
}

export async function upsertModelScenes(body: ModelSceneUpsert | ModelSceneUpsert[]): Promise<ModelScene[]> {
  const res = await fetch(apiUrl('/api/models/scenes'), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<ModelScene[]>(res)
}

export async function fetchModelCatalog(tenantId?: string): Promise<ModelCatalog> {
  const qs = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  const res = await fetch(apiUrl(`/api/models/catalog${qs}`), { headers: apiHeaders() })
  const raw = await parseApiResponse<ModelCatalog>(res)
  return {
    providers: raw.providers ?? [],
    definitions: (raw.definitions ?? []).map((d) => ({
      ...d,
      capabilities: normalizeCapabilities(d.capabilities),
    })),
    scenes: raw.scenes ?? [],
  }
}
