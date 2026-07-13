/** 节点 params 执行策略读写 — SSOT 默认值来自 workflow-manager Nacos */

import type { WorkflowNodeDefaultsResponse, WorkflowPlan } from '../api/workflows'

/** API 不可用时的客户端兜底（与 sunshine-workflow-manager.yaml 对齐） */
export const FALLBACK_NODE_DEFAULTS: WorkflowNodeDefaultsResponse = {
  defaults: { maxAttempts: 2, backoffMs: 500, onFailure: 'continue' },
  byType: {
    rag: { maxAttempts: 1, backoffMs: 500, onFailure: 'continue' },
    tool: { maxAttempts: 2, backoffMs: 500, onFailure: 'continue' },
    agent: { maxAttempts: 1, backoffMs: 500, onFailure: 'continue' },
    answer: { maxAttempts: 2, backoffMs: 500, onFailure: 'fail_fast' },
    join: { maxAttempts: 2, backoffMs: 500, onFailure: 'continue' },
    'parallel-gateway': { maxAttempts: 1, backoffMs: 500, onFailure: 'continue' },
    'exclusive-gateway': { maxAttempts: 1, backoffMs: 500, onFailure: 'continue' },
    llm: { maxAttempts: 2, backoffMs: 500, onFailure: 'continue' },
  },
  catalog: { intentAfter: '{query}将按「{displayName}」流程处理' },
  nodeParams: {
    rag: { topK: 3, kbIdEmptyLabel: '（会话默认）' },
    agent: { maxIters: 8, kbIdEmptyLabel: '（会话默认）' },
  },
}

export function resolveNodeDefaults(
  defaults: WorkflowNodeDefaultsResponse | null | undefined,
): WorkflowNodeDefaultsResponse {
  return defaults ?? FALLBACK_NODE_DEFAULTS
}

export const RETRY_PARAM_KEYS = {
  maxAttempts: 'retry.maxAttempts',
  backoffMs: 'retry.backoffMs',
  onFailure: 'retry.onFailure',
} as const

export const ON_FAILURE_OPTIONS = [
  { label: '继续（continue）', value: 'continue' },
  { label: '快速失败（fail_fast）', value: 'fail_fast' },
  { label: '跳过（skip）', value: 'skip' },
  { label: '降级 ReAct（fallback_react）', value: 'fallback_react' },
] as const

const RESERVED_TOOL_PARAM_KEYS = new Set([
  'tool',
  'output.mode',
  'output.extract',
  RETRY_PARAM_KEYS.maxAttempts,
  RETRY_PARAM_KEYS.backoffMs,
  RETRY_PARAM_KEYS.onFailure,
])

export function resolveRetryForType(
  nodeType: string,
  defaults: WorkflowNodeDefaultsResponse,
) {
  return defaults.byType[nodeType] ?? defaults.defaults
}

export function buildRetryParams(
  nodeType: string,
  defaults: WorkflowNodeDefaultsResponse,
): Record<string, string> {
  const r = resolveRetryForType(nodeType, defaults)
  return {
    [RETRY_PARAM_KEYS.maxAttempts]: String(r.maxAttempts),
    [RETRY_PARAM_KEYS.backoffMs]: String(r.backoffMs),
    [RETRY_PARAM_KEYS.onFailure]: r.onFailure,
  }
}

export function hasRetryParams(params?: Record<string, unknown>): boolean {
  if (!params) return false
  const max = params[RETRY_PARAM_KEYS.maxAttempts]
  const backoff = params[RETRY_PARAM_KEYS.backoffMs]
  const onFailure = params[RETRY_PARAM_KEYS.onFailure]
  return max != null && String(max).trim() !== ''
    && backoff != null && String(backoff).trim() !== ''
    && onFailure != null && String(onFailure).trim() !== ''
}

export function ensurePlanRetryDefaults(
  plan: WorkflowPlan,
  defaults: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const nodes = (plan.nodes ?? []).map(n => {
    if (n.type === 'start' || hasRetryParams(n.params)) return n
    return {
      ...n,
      params: { ...n.params, ...buildRetryParams(n.type, defaults) },
    }
  })
  return { ...plan, nodes }
}

/** 补齐执行策略与节点参数默认值（加载/校验/保存前统一调用） */
export function applyPlanDefaults(
  plan: WorkflowPlan,
  defaults?: WorkflowNodeDefaultsResponse | null,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(defaults)
  return ensurePlanNodeParamDefaults(ensurePlanRetryDefaults(plan, resolved), resolved)
}

export function ensurePlanNodeParamDefaults(
  plan: WorkflowPlan,
  defaults: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const rag = defaults.nodeParams?.rag
  const agent = defaults.nodeParams?.agent
  const nodes = (plan.nodes ?? []).map(n => {
    if (n.type === 'rag' && rag) {
      const params = { ...n.params }
      if (params.topK == null || String(params.topK).trim() === '') {
        params.topK = String(rag.topK ?? 3)
      }
      if (params.query == null || String(params.query).trim() === '') {
        params.query = '{{start.userQuery}}'
      }
      return { ...n, params }
    }
    if (n.type === 'agent' && agent) {
      const params = { ...n.params }
      if (params.maxIters == null || String(params.maxIters).trim() === '') {
        params.maxIters = String(agent.maxIters ?? 8)
      }
      return { ...n, params }
    }
    return n
  })
  return { ...plan, nodes }
}

export function defaultCatalogIntentAfter(defaults: WorkflowNodeDefaultsResponse | null): string {
  return defaults?.catalog?.intentAfter?.trim()
    || '{query}将按「{displayName}」流程处理'
}

export function readRagTopK(
  params: Record<string, unknown> | undefined,
  defaults: WorkflowNodeDefaultsResponse | null,
): number {
  const raw = params?.topK
  if (raw != null && String(raw).trim() !== '') {
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) return n
  }
  return defaults?.nodeParams?.rag?.topK ?? 3
}

export function readAgentMaxIters(
  params: Record<string, unknown> | undefined,
  defaults: WorkflowNodeDefaultsResponse | null,
): number {
  const raw = params?.maxIters
  if (raw != null && String(raw).trim() !== '') {
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) return n
  }
  return defaults?.nodeParams?.agent?.maxIters ?? 8
}

export function agentKbIdEmptyLabel(defaults: WorkflowNodeDefaultsResponse | null): string {
  return defaults?.nodeParams?.agent?.kbIdEmptyLabel?.trim()
    || defaults?.nodeParams?.rag?.kbIdEmptyLabel?.trim()
    || '（会话默认）'
}

export const SESSION_KB_VALUE = '__session_default__'

export function ragKbIdEmptyLabel(defaults: WorkflowNodeDefaultsResponse | null): string {
  return defaults?.nodeParams?.rag?.kbIdEmptyLabel?.trim() || '（会话默认）'
}

export function resolveKbSelectValue(
  params: Record<string, unknown> | undefined,
): string {
  const raw = params?.kbId
  if (raw != null && String(raw).trim() !== '') return String(raw).trim()
  return SESSION_KB_VALUE
}

export function patchKbIdFromSelect(selected: string): string | null {
  return selected === SESSION_KB_VALUE ? null : selected
}

export function displayAgentKbId(
  params: Record<string, unknown> | undefined,
  defaults: WorkflowNodeDefaultsResponse | null,
  readOnly: boolean,
): string {
  const raw = params?.kbId
  if (raw != null && String(raw).trim() !== '') return String(raw)
  return readOnly ? agentKbIdEmptyLabel(defaults) : ''
}

export function displayRagKbId(
  params: Record<string, unknown> | undefined,
  defaults: WorkflowNodeDefaultsResponse | null,
  readOnly: boolean,
): string {
  const raw = params?.kbId
  if (raw != null && String(raw).trim() !== '') return String(raw)
  return readOnly ? ragKbIdEmptyLabel(defaults) : ''
}

/** @param strict 为 true 时不自动补齐默认值（发布/校验 DAG 用） */
export function collectRetryValidationIssues(
  plan: WorkflowPlan,
  defaults?: WorkflowNodeDefaultsResponse | null,
  strict = false,
): string[] {
  const normalized = defaults && !strict ? applyPlanDefaults(plan, defaults) : plan
  const issues: string[] = []
  for (const node of normalized.nodes ?? []) {
    if (node.type === 'start') continue
    const label = node.displayName || node.id
    if (!hasRetryParams(node.params)) {
      issues.push(`节点「${label}」缺少完整执行策略（重试次数 / 间隔 / 失败后策略）`)
      continue
    }
    const max = Number(node.params?.[RETRY_PARAM_KEYS.maxAttempts])
    if (!Number.isFinite(max) || max < 1 || max > 10) {
      issues.push(`节点「${label}」retry.maxAttempts 须在 1–10 之间`)
    }
    const backoff = Number(node.params?.[RETRY_PARAM_KEYS.backoffMs])
    if (!Number.isFinite(backoff) || backoff < 100 || backoff > 30_000) {
      issues.push(`节点「${label}」retry.backoffMs 须在 100–30000 之间`)
    }
    const onFailure = String(node.params?.[RETRY_PARAM_KEYS.onFailure] ?? '')
    if (!ON_FAILURE_OPTIONS.some(o => o.value === onFailure)) {
      issues.push(`节点「${label}」retry.onFailure 非法`)
    }
  }
  return issues
}

export function readRetryMaxAttempts(
  params: Record<string, unknown> | undefined,
  nodeType?: string,
  defaults?: WorkflowNodeDefaultsResponse | null,
): number {
  const raw = params?.[RETRY_PARAM_KEYS.maxAttempts]
  if (raw != null && String(raw).trim() !== '') {
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) return n
  }
  if (defaults && nodeType) return resolveRetryForType(nodeType, defaults).maxAttempts
  return 1
}

export function readRetryBackoffMs(
  params: Record<string, unknown> | undefined,
  nodeType?: string,
  defaults?: WorkflowNodeDefaultsResponse | null,
): number {
  const raw = params?.[RETRY_PARAM_KEYS.backoffMs]
  if (raw != null && String(raw).trim() !== '') {
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) return n
  }
  if (defaults && nodeType) return resolveRetryForType(nodeType, defaults).backoffMs
  return 500
}

export function readRetryOnFailure(
  params: Record<string, unknown> | undefined,
  nodeType?: string,
  defaults?: WorkflowNodeDefaultsResponse | null,
): string {
  const raw = params?.[RETRY_PARAM_KEYS.onFailure]
  const val = raw != null ? String(raw) : ''
  if (ON_FAILURE_OPTIONS.some(o => o.value === val)) return val
  if (defaults && nodeType) return resolveRetryForType(nodeType, defaults).onFailure
  return 'continue'
}

export function patchNodeParams(
  params: Record<string, unknown> | undefined,
  patch: Record<string, string | number | null | undefined>,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(params ?? {}) }
  for (const [key, val] of Object.entries(patch)) {
    if (val === '' || val == null) {
      delete next[key]
    } else {
      next[key] = String(val)
    }
  }
  return next
}

export function toolExtraParamsLines(params?: Record<string, unknown>): string {
  if (!params) return ''
  return Object.entries(params)
    .filter(([k]) => !RESERVED_TOOL_PARAM_KEYS.has(k))
    .map(([k, v]) => `${k}=${String(v ?? '')}`)
    .join('\n')
}

export function parseToolExtraParamsLines(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq <= 0) continue
    const key = trimmed.slice(0, eq).trim()
    if (!key || RESERVED_TOOL_PARAM_KEYS.has(key)) continue
    out[key] = trimmed.slice(eq + 1).trim()
  }
  return out
}

export function mergeToolExtraParams(
  params: Record<string, unknown> | undefined,
  extraLines: string,
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(params ?? {}) }
  for (const k of Object.keys(next)) {
    if (!RESERVED_TOOL_PARAM_KEYS.has(k)) {
      delete next[k]
    }
  }
  Object.assign(next, parseToolExtraParamsLines(extraLines))
  return next
}

/** agent.params.tools — 存库为逗号分隔 Catalog ID */
export function parseAgentToolsParam(raw: unknown): string[] {
  if (raw == null) return []
  const text = String(raw).trim()
  if (!text) return []
  return text.split(',').map(s => s.trim()).filter(Boolean)
}

export function formatAgentToolsParam(ids: string[] | null | undefined): string {
  return (ids ?? []).map(s => s.trim()).filter(Boolean).join(',')
}
