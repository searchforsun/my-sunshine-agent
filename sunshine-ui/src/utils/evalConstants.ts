export const DEFAULT_EVAL_SUITE_KEY = 'sunshine-regression'

export const BUILTIN_SUITE_KEYS = [
  'sunshine-regression',
  'sunshine-adversarial',
  'sunshine-smoke',
] as const

/** 自定义评测集 Key：字母/数字/下划线，不能以数字开头 */
export const SUITE_KEY_PATTERN = /^[a-zA-Z_][a-zA-Z0-9_]*$/

export function suiteKeyFormatError(key: string): string | null {
  const trimmed = key.trim()
  if (!trimmed) return 'Key 不能为空'
  if (!SUITE_KEY_PATTERN.test(trimmed)) {
    return 'Key 仅支持字母、数字、下划线，且不能以数字开头'
  }
  return null
}

export const EVAL_STRATEGY_OPTIONS = [
  { label: '跟随应用配置', value: '' },
  { label: 'Hybrid + Rerank', value: 'hybrid+rerank' },
  { label: 'Hybrid', value: 'hybrid' },
  { label: 'Vector', value: 'vector' },
]

export const EVAL_JOB_STATUS_LABEL: Record<string, string> = {
  pending: '排队中',
  running: '运行中',
  done: '已完成',
  failed: '失败',
}

export const EVAL_GATE_LABEL = {
  pass: '通过',
  fail: '未通过',
} as const

/** 扁平 config_json SSOT，与 MySQL 种子 / EvalSuiteConfigParser 一致 */
export interface EvalSuiteGates {
  recallAt3Min?: number
  recallAt5Min?: number
  mrrMin?: number
  emptyRatePositiveMax?: number
  emptyRateNegativeMin?: number
  latencyP95MsMax?: number
}

export interface EvalSuiteConfig {
  topK: number[]
  minScore: number
  gates: EvalSuiteGates
}

export const DEFAULT_EVAL_TOP_K = [3, 5, 10] as const
export const DEFAULT_EVAL_MIN_SCORE = 0.48

export const DEFAULT_EVAL_GATES: EvalSuiteGates = {
  recallAt3Min: 0.95,
  recallAt5Min: 0.98,
  mrrMin: 0.92,
  emptyRatePositiveMax: 0,
  emptyRateNegativeMin: 0.95,
  latencyP95MsMax: 500,
}

export const EVAL_TOP_K_OPTIONS = [3, 5, 10, 20].map((v) => ({ label: String(v), value: v }))

const EVAL_GATE_KEYS = [
  'recallAt3Min',
  'recallAt5Min',
  'mrrMin',
  'emptyRatePositiveMax',
  'emptyRateNegativeMin',
  'latencyP95MsMax',
] as const satisfies readonly (keyof EvalSuiteGates)[]

function asNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  return undefined
}

function asTopK(value: unknown): number[] {
  if (!Array.isArray(value)) return [...DEFAULT_EVAL_TOP_K]
  const nums = value
    .map((item) => (typeof item === 'number' ? item : Number(item)))
    .filter((n) => Number.isFinite(n) && n > 0)
  if (!nums.length) return [...DEFAULT_EVAL_TOP_K]
  return [...new Set(nums)].sort((a, b) => a - b)
}

function extractGates(source: Record<string, unknown> | undefined): EvalSuiteGates {
  if (!source) return {}
  const gates: EvalSuiteGates = {}
  for (const key of EVAL_GATE_KEYS) {
    const n = asNumber(source[key])
    if (n != null) gates[key] = n
  }
  return gates
}

/** 将 API config_json 归一为扁平结构 */
export function normalizeEvalSuiteConfig(
  raw: Record<string, unknown> | null | undefined,
): EvalSuiteConfig {
  const src = raw ?? {}
  const topK = asTopK(src.topK)
  const minScore = asNumber(src.minScore) ?? DEFAULT_EVAL_MIN_SCORE
  const gatesFromRoot = extractGates(
    src.gates && typeof src.gates === 'object' && !Array.isArray(src.gates)
      ? (src.gates as Record<string, unknown>)
      : undefined,
  )
  return { topK, minScore, gates: { ...DEFAULT_EVAL_GATES, ...gatesFromRoot } }
}

export function defaultEvalSuiteConfig(): EvalSuiteConfig {
  return {
    topK: [...DEFAULT_EVAL_TOP_K],
    minScore: DEFAULT_EVAL_MIN_SCORE,
    gates: { ...DEFAULT_EVAL_GATES },
  }
}

/** 保存时仅提交扁平字段，剥离 corpus / eval / hooks 等遗留键 */
export function serializeEvalSuiteConfig(config: EvalSuiteConfig): Record<string, unknown> {
  const mergedGates = { ...DEFAULT_EVAL_GATES, ...config.gates }
  const gates: Record<string, number> = {}
  for (const key of EVAL_GATE_KEYS) {
    const value = mergedGates[key]
    if (value != null && Number.isFinite(value)) gates[key] = value
  }
  return {
    topK: config.topK,
    minScore: config.minScore,
    gates,
  }
}

export function evalSuiteOptionLabel(displayName: string, itemCount: number): string {
  return `${displayName}（${itemCount} 条）`
}

/** 评测条目分类：按问法/检索类型分组（评测报告 by_category_recall_at_3），非业务域 */
export const EVAL_CATEGORY_OPTIONS = [
  { label: '自定义', value: 'custom' },
  { label: '负例', value: 'negative' },
  { label: '难例/口语', value: 'adversarial' },
  { label: '多跳综合', value: 'multihop' },
  { label: '事实查询', value: 'factual' },
  { label: '流程步骤', value: 'procedural' },
  { label: '制度规则', value: 'policy' },
  { label: '概念定义', value: 'definition' },
  { label: '对比辨析', value: 'comparison' },
  { label: '场景综合', value: 'scenario' },
  { label: '边界例外', value: 'edge_case' },
  { label: '时效条件', value: 'temporal' },
  { label: '权限审批', value: 'authorization' },
  { label: '数值标准', value: 'quantitative' },
  { label: '资格适用', value: 'eligibility' },
  { label: '例外豁免', value: 'exception' },
  { label: '材料要件', value: 'documentation' },
  { label: '违规后果', value: 'violation' },
] as const

export type EvalCategory = (typeof EVAL_CATEGORY_OPTIONS)[number]['value']

export const DEFAULT_EVAL_CATEGORY: EvalCategory = 'custom'

const EVAL_CATEGORY_LABEL: Record<string, string> = Object.fromEntries(
  EVAL_CATEGORY_OPTIONS.map((o) => [o.value, o.label]),
)

/** 列表展示：已知枚举显示中文，历史/种子遗留值原样显示 */
export function formatEvalCategory(category: string | null | undefined): string {
  const key = category?.trim()
  if (!key) return '—'
  return EVAL_CATEGORY_LABEL[key] ?? key
}

export function isEvalNegativeCategory(category: string | null | undefined): boolean {
  return category?.trim() === 'negative'
}

export function kbCustomSuiteKey(kbId: string): string {
  const safe = kbId.trim().replace(/[^a-zA-Z0-9_-]/g, '-')
  return `${safe || 'default'}-custom`
}

export function evalJobProgressPct(job: {
  status: string
  progressPct?: number | null
  processedItems?: number | null
  totalItems?: number | null
}): number {
  if (job.progressPct != null && Number.isFinite(job.progressPct)) {
    return Math.round(job.progressPct)
  }
  if (job.status === 'done') return 100
  if (job.status === 'failed') return 100
  if (job.status === 'running') return 10
  return 0
}

export function evalJobProgressText(job: {
  status: string
  processedItems?: number | null
  totalItems?: number | null
}): string {
  if (job.totalItems != null && job.totalItems > 0) {
    const done = job.processedItems ?? 0
    return `${done} / ${job.totalItems} 条`
  }
  return EVAL_JOB_STATUS_LABEL[job.status] ?? job.status
}

import type { EvalJobSummary } from '../api/ragAdmin'
import type { KbAppliedConfig } from '../composables/useKbWorkbenchContext'

export function isEvalJobActive(status: string): boolean {
  return status === 'pending' || status === 'running'
}

/** 运行中任务是否与当前应用配置一致（同 kb 下同一配置仅允许一个评测） */
export function jobMatchesAppliedConfig(
  job: Pick<EvalJobSummary, 'configVersionId' | 'status'>,
  applied: KbAppliedConfig,
): boolean {
  if (!isEvalJobActive(job.status)) return false
  if (applied.versionId != null) {
    return job.configVersionId === applied.versionId
  }
  return job.configVersionId == null
}

export function formatEvalTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  return iso.replace('T', ' ').slice(0, 19)
}
