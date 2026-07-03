/** 评测优化建议 path → 中文展示名（与 rag-service ConfigScope + RagConfigSchemaService 对齐） */

import type { ConfigSuggestionItem, EvalSuggestResult } from '../api/ragAdmin'

const TOP_SCOPE_LABEL: Record<string, string> = {
  search: '检索参数',
  rerank: 'Rerank 参数',
  chunk: '分段参数',
}

const REWRITE_SCOPE_LABEL: Record<string, string> = {
  rag: 'RAG 改写',
  hyde: 'HyDE',
  emptyRecall: '零命中改写',
}

const FIELD_LABEL: Record<string, Record<string, string>> = {
  search: {
    minScore: '向量/IP 下限',
    strategy: '默认策略',
    rrfK: 'RRF 常数 k',
    hybridPoolSize: '混合召回池',
    defaultTopK: '默认 TopK',
  },
  rerank: {
    enabled: '启用 Rerank',
    minScore: 'Rerank 下限',
    minRelevance: 'Relevance 下限',
  },
  chunk: {
    maxSize: '分段最大字符',
  },
  'rewrite.rag': {
    enabled: '启用 RAG 改写',
    model: '模型',
    systemPrompt: '系统提示词',
  },
  'rewrite.hyde': {
    enabled: '启用 HyDE',
    model: '模型',
    maxChars: '最大字符',
    systemPrompt: '系统提示词',
  },
  'rewrite.emptyRecall': {
    enabled: '启用零命中改写',
    model: '模型',
    maxAlternatives: '备选 query 数',
    systemPrompt: '系统提示词',
  },
}

function fieldLabel(scopeKey: string, fieldId: string): string {
  return FIELD_LABEL[scopeKey]?.[fieldId] ?? fieldId
}

/** 如 search.minScore → 「检索参数 · 向量/IP 下限」 */
export function configPathDisplayLabel(path: string): string {
  if (!path?.trim()) return '—'
  const parts = path.split('.')
  if (parts[0] === 'rewrite' && parts.length >= 3) {
    const rewriteKind = parts[1]
    const fieldId = parts.slice(2).join('.')
    const scopeLabel = REWRITE_SCOPE_LABEL[rewriteKind] ?? rewriteKind
    return `${scopeLabel} · ${fieldLabel(`rewrite.${rewriteKind}`, fieldId)}`
  }
  if (parts.length >= 2 && TOP_SCOPE_LABEL[parts[0]]) {
    const scopeLabel = TOP_SCOPE_LABEL[parts[0]]
    const fieldId = parts.slice(1).join('.')
    return `${scopeLabel} · ${fieldLabel(parts[0], fieldId)}`
  }
  return path
}

export function isConfigPromptPath(path: string): boolean {
  return path.endsWith('.systemPrompt') || path.endsWith('systemPrompt')
}

/** 去掉 LLM 可能带的前导破折号 */
export function normalizeSuggestReason(reason: string | undefined | null): string {
  if (!reason?.trim()) return ''
  return reason.replace(/^[—\-–]+\s*/, '').trim()
}

/** Prompt 类 textSuggestions（如 rewrite.rag.systemPrompt）可写入 bundle payload */
export function isApplicableTextSuggestionForConfig(item: { target?: string; kind?: string; proposed?: string }): boolean {
  const target = item.target?.trim()
  if (!target || !item.proposed?.trim()) return false
  if (item.kind === 'eval_query' || item.kind === 'document') return false
  return isConfigPromptPath(target)
}

export function textSuggestionToConfigItem(item: {
  target?: string
  kind?: string
  current?: string
  proposed?: string
  reason?: string
}): ConfigSuggestionItem | null {
  if (!isApplicableTextSuggestionForConfig(item)) return null
  return {
    path: item.target!.trim(),
    current: item.current,
    proposed: item.proposed!.trim(),
    reason: item.reason,
  }
}

/** 合并数值参数建议与可写入 bundle 的 Prompt 建议（rewrite.*.systemPrompt 等） */
export function collectSuggestionsForApply(result: EvalSuggestResult | null | undefined): ConfigSuggestionItem[] {
  if (!result) return []
  const fromConfig = (result.suggestions ?? []).filter(
    (item) => item.path && item.proposed !== undefined,
  )
  const fromText = (result.textSuggestions ?? [])
    .map(textSuggestionToConfigItem)
    .filter((item): item is ConfigSuggestionItem => item != null)
  return [...fromConfig, ...fromText]
}
