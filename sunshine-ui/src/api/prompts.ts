import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export type PromptKind =
  | 'system'
  | 'mode-overlay'
  | 'react-fragment'
  | 'intent'
  | 'planner'
  | 'answer'
  | 'timeline'
  | 'rewrite'
  | 'hitl'
  | 'memory'
  | 'scope'
  | 'routing-rule'
  | string

export interface PromptListItem {
  id: string
  kind: PromptKind
  displayName: string
  enabled: boolean
  priority: number
  activeVersion: number
  catalogVersion: number
  updatedAt?: string | null
}

export interface PromptVersionItem {
  version: number
  status: string
  contentText: string | null
  contentJson: string | null
  changeNote: string | null
  maintainer: string | null
  createdAt?: string | null
}

export interface PromptDetail {
  id: string
  kind: PromptKind
  displayName: string
  description: string | null
  enabled: boolean
  priority: number
  activeVersion: number
  catalogVersion: number
  createdAt?: string | null
  updatedAt?: string | null
  activeVersionContent: PromptVersionItem | null
}

export interface PromptCreateBody {
  id: string
  kind: string
  displayName: string
  description?: string
  priority?: number
  enabled?: boolean
  status?: string
  contentText?: string
  contentJson?: string
  changeNote?: string
  maintainer?: string
}

export interface PromptUpdateBody {
  displayName?: string
  description?: string
  priority?: number
  expectedUpdatedAt?: string | null
}

export interface PromptVersionBody {
  status?: string
  contentText?: string | null
  contentJson?: string | null
  changeNote?: string
  maintainer?: string
  expectedUpdatedAt?: string | null
}

export interface RoutingRuleContent {
  matchType: string
  match?: string
  patterns?: string[]
  domainGroups?: Record<string, string[]>
  minDomainGroups?: number
  plan?: {
    mode?: string
    workflowId?: string | null
    params?: Record<string, string>
  }
}

export interface RoutingRuleInput {
  id: string
  priority?: number
  enabled?: boolean
  contentJson: string
}

export interface RoutingWarningItem {
  message: string
}

export interface RoutingValidateResponse {
  warnings: RoutingWarningItem[]
}

export interface RoutingDryRunResponse {
  matchedRuleId: string | null
  /** rule=同轨规则命中；l3=将走 L3 补绑定（不改模式） */
  stage: string | null
  plan?: {
    mode?: string
    workflowId?: string | null
    params?: Record<string, string>
  } | null
}

export const PROMPT_KIND_LABELS: Record<string, string> = {
  system: '系统',
  'mode-overlay': '模式叠加',
  'react-fragment': 'ReAct 片段',
  react: 'ReAct 运行时',
  intent: '意图',
  planner: '规划',
  answer: '回答',
  timeline: '时间线',
  rewrite: '改写',
  hitl: 'HITL',
  memory: '记忆',
  scope: '范围',
  'routing-rule': '路由规则',
  sandbox: '沙箱',
  rag: '知识库检索',
  'plan-workflow': '动态规划',
}

export function promptKindLabel(kind: string): string {
  return PROMPT_KIND_LABELS[kind] ?? kind
}

/** 列表/详情展示用：去掉 Tab 内重复的 kind 前缀 */
export function shortPromptId(id: string): string {
  if (id.startsWith('routing-rule.')) return id.slice('routing-rule.'.length)
  return id
}

/** 新建时补全存储 ID 前缀（用户可只填短名） */
export function ensurePromptIdPrefix(id: string, kind: string): string {
  const trimmed = id.trim()
  if (!trimmed) return trimmed
  if (kind === 'routing-rule' && !trimmed.startsWith('routing-rule.')) {
    return `routing-rule.${trimmed}`
  }
  return trimmed
}

export async function listPrompts(kind?: string, enabled?: boolean): Promise<PromptListItem[]> {
  const qs = new URLSearchParams()
  if (kind) qs.set('kind', kind)
  if (enabled !== undefined) qs.set('enabled', String(enabled))
  const suffix = qs.toString() ? `?${qs}` : ''
  const res = await fetch(apiUrl(`/api/prompts${suffix}`), { headers: apiHeaders() })
  return parseApiResponse<PromptListItem[]>(res)
}

export async function getPrompt(id: string): Promise<PromptDetail> {
  const res = await fetch(apiUrl(`/api/prompts/${encodeURIComponent(id)}`), {
    headers: apiHeaders(),
  })
  return parseApiResponse<PromptDetail>(res)
}

export async function createPrompt(body: PromptCreateBody): Promise<PromptDetail> {
  const res = await fetch(apiUrl('/api/prompts'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<PromptDetail>(res)
}

export async function updatePrompt(id: string, body: PromptUpdateBody): Promise<PromptDetail> {
  const res = await fetch(apiUrl(`/api/prompts/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<PromptDetail>(res)
}

export async function setPromptEnabled(id: string, enabled: boolean): Promise<PromptDetail> {
  const res = await fetch(apiUrl(`/api/prompts/${encodeURIComponent(id)}/enable`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  return parseApiResponse<PromptDetail>(res)
}

export async function listPromptVersions(id: string): Promise<PromptVersionItem[]> {
  const res = await fetch(apiUrl(`/api/prompts/${encodeURIComponent(id)}/versions`), {
    headers: apiHeaders(),
  })
  return parseApiResponse<PromptVersionItem[]>(res)
}

export async function addPromptVersion(id: string, body: PromptVersionBody): Promise<PromptVersionItem> {
  const res = await fetch(apiUrl(`/api/prompts/${encodeURIComponent(id)}/versions`), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<PromptVersionItem>(res)
}

export async function publishPrompt(
  id: string,
  body?: { version?: number; maintainer?: string; expectedUpdatedAt?: string | null },
): Promise<PromptDetail> {
  const res = await fetch(apiUrl(`/api/prompts/${encodeURIComponent(id)}/publish`), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {}),
  })
  return parseApiResponse<PromptDetail>(res)
}

export async function rollbackPrompt(
  id: string,
  version: number,
  expectedUpdatedAt?: string | null,
): Promise<PromptDetail> {
  const res = await fetch(apiUrl(`/api/prompts/${encodeURIComponent(id)}/rollback`), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ version, expectedUpdatedAt: expectedUpdatedAt ?? null }),
  })
  return parseApiResponse<PromptDetail>(res)
}

export async function validateRoutingRules(
  rules?: RoutingRuleInput[],
): Promise<RoutingValidateResponse> {
  const res = await fetch(apiUrl('/api/prompts/routing/validate'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(rules ? { rules } : {}),
  })
  return parseApiResponse<RoutingValidateResponse>(res)
}

export async function dryRunRouting(
  query: string,
  mode: string,
): Promise<RoutingDryRunResponse> {
  const res = await fetch(apiUrl('/api/prompts/routing/dry-run'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, mode }),
  })
  return parseApiResponse<RoutingDryRunResponse>(res)
}

export function parseRoutingContentJson(raw: string | null | undefined): RoutingRuleContent {
  if (!raw?.trim()) {
    return {
      matchType: 'regex',
      match: 'any',
      patterns: [],
      domainGroups: {},
      minDomainGroups: 2,
      plan: { mode: 'react', workflowId: null, params: {} },
    }
  }
  try {
    const parsed = JSON.parse(raw) as RoutingRuleContent
    return {
      matchType: parsed.matchType || 'regex',
      match: parsed.match || 'any',
      patterns: Array.isArray(parsed.patterns) ? parsed.patterns : [],
      domainGroups: parsed.domainGroups && typeof parsed.domainGroups === 'object'
        ? parsed.domainGroups
        : {},
      minDomainGroups: typeof parsed.minDomainGroups === 'number' ? parsed.minDomainGroups : 2,
      plan: {
        mode: parsed.plan?.mode || 'react',
        workflowId: parsed.plan?.workflowId ?? null,
        params: parsed.plan?.params ?? {},
      },
    }
  } catch {
    return {
      matchType: 'regex',
      match: 'any',
      patterns: [],
      domainGroups: {},
      minDomainGroups: 2,
      plan: { mode: 'react', workflowId: null, params: {} },
    }
  }
}

export function serializeRoutingContent(content: RoutingRuleContent): string {
  const payload: RoutingRuleContent = {
    matchType: content.matchType,
    match: content.match || 'any',
    patterns: content.patterns ?? [],
    domainGroups: content.domainGroups ?? {},
    minDomainGroups: content.minDomainGroups ?? 2,
    plan: {
      mode: content.plan?.mode || 'react',
      workflowId: content.plan?.workflowId || null,
      params: content.plan?.params ?? {},
    },
  }
  return JSON.stringify(payload)
}

export function parseFragmentMeta(raw: string | null | undefined): {
  attachTo: string
  sortOrder: number
} {
  if (!raw?.trim()) return { attachTo: 'mode-overlay.react', sortOrder: 0 }
  try {
    const parsed = JSON.parse(raw) as { attachTo?: string; sortOrder?: number }
    return {
      attachTo: parsed.attachTo || 'mode-overlay.react',
      sortOrder: typeof parsed.sortOrder === 'number' ? parsed.sortOrder : 0,
    }
  } catch {
    return { attachTo: 'mode-overlay.react', sortOrder: 0 }
  }
}

export function serializeFragmentMeta(attachTo: string, sortOrder: number): string {
  return JSON.stringify({ attachTo, sortOrder })
}
