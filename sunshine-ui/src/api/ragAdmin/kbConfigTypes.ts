export interface ConfigFieldSchema {
  fieldId: string
  label: string
  type: string
  min?: number | null
  max?: number | null
  scope: string
  currentValue: unknown
  enumValues?: string[] | null
}

export interface ConfigScopeGroup {
  scope: string
  label: string
  dataId: string
  nacosPath: string
  fields: ConfigFieldSchema[]
}

export interface EffectiveRagConfig {
  minScore: number
  strategy: string
  rrfK: number
  hybridPoolSize: number
  rerankMinScore: number
  chunkMaxSize: number
}

export interface ConfigSchemaResponse {
  scopes: ConfigScopeGroup[]
  effective: EffectiveRagConfig
}

export interface FailedEvalSample {
  queryId: string
  query: string
  expectedDocNames: string[]
  topDocNames: string[]
}

export interface PublishGateFailure {
  recallAt5: number
  baselineRecallAt5: number
  failedSamples: FailedEvalSample[]
}

export interface ConfigBundleDraftView {
  draftVersionId: number
  draftVersionNo: number
  payload: Record<string, unknown>
  activePublishedVersionId: number | null
  activePublishedVersionNo: number | null
}

export interface ConfigVersionSummary {
  id: number
  versionNo: number
  status: string
  createdAt: string
  publishedAt: string | null
  active: boolean
  recallAt5: number | null
  changeNote: string | null
  createdBy: string | null
}

export interface SubmitEvalResult {
  versionId: number
  versionNo: number
  status: string
}

export interface PublishBundleResult {
  versionId: number
  versionNo: number
  eval: { recallAt5: number; baselineRecallAt5: number; passedGate: boolean; failedSamples: FailedEvalSample[] }
  reportId: number
}

export interface ConfigSuggestionItem {
  path: string
  current?: unknown
  proposed: unknown
  reason?: string
}

export interface TextSuggestionItem {
  target: string
  kind: string
  current?: string
  proposed: string
  reason?: string
}

export interface EvalSuggestResult {
  diagnosis: string
  suggestions: ConfigSuggestionItem[]
  textSuggestions?: TextSuggestionItem[]
}

export interface EvalSuiteSummary {
  id: number
  suiteKey: string
  displayName: string
  kind: string
  format: string
  itemCount: number
  status: string
  builtin: boolean
  createdAt: string
}

export interface EvalSuiteItemView {
  itemKey: string
  sortOrder: number
  queryText: string
  itemType: string
  relevantDocIds: string[]
  relevantKeywords: string[]
  category: string | null
  expectEmpty: boolean
}

export interface EvalSuiteDetail extends EvalSuiteSummary {
  description: string | null
  contentRef: string | null
  hooks: Record<string, unknown>
  config: Record<string, unknown>
  content: string | null
  items: EvalSuiteItemView[]
}

export interface EvalSuiteCreateRequest {
  suiteKey: string
  displayName?: string
  description?: string
  kind?: string
  config?: Record<string, unknown>
  hooks?: Record<string, unknown>
  content?: string
}

export interface EvalSuiteUpdateRequest {
  displayName?: string
  description?: string
  config?: Record<string, unknown>
  hooks?: Record<string, unknown>
}

export interface EvalSuiteQueryRequest {
  action: 'add' | 'update' | 'delete'
  id?: string
  query?: string
  relevantDocIds?: string[]
  category?: string
}

export interface EvalJobSummary {
  jobId: number
  kbId: string
  suite: string
  suiteKey: string
  status: string
  configVersionId: number | null
  configVersionNo: number | null
  reportId: number | null
  recallAt5: number | null
  passedGate: boolean | null
  createdAt: string
  finishedAt: string | null
}

export interface EvalJobStatus {
  jobId: number
  tenantId: string
  kbId: string
  suite: string
  status: string
  reportId: number | null
  configVersionId: number | null
  totalItems: number | null
  processedItems: number | null
  progressPct: number | null
  createdAt: string
  finishedAt: string | null
}

export interface EvalReportView {
  reportId: number
  jobId: number
  recallAt5: number | null
  mrr: number | null
  passedGate: boolean | null
  baselineRecallAt5: number | null
  summary: Record<string, unknown>
  failedSamples?: Array<Record<string, unknown>>
  suggestions?: EvalSuggestResult | null
  reportMdPath: string | null
  reportJsonPath: string | null
}
