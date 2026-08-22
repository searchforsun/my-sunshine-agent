import type { ConfigVersionSummary } from '../api/ragAdmin'
import type { EvalJobSummary } from '../api/ragAdmin'
import type { KbAppliedConfig } from '../composables/useKbWorkbenchContext'
import { formatSkillVersionTime } from './formatSkillVersionTime'

export type ConfigVersionUiStatus =
  | 'draft'
  | 'pending_eval'
  | 'evaluating'
  | 'eval_passed'
  | 'eval_failed'
  | 'live'
  | 'inactive'

export const PIPELINE_STATUSES = [
  'draft',
  'pending_eval',
  'evaluating',
  'eval_passed',
  'eval_failed',
] as const

export type PipelineStatus = (typeof PIPELINE_STATUSES)[number]

export function isPipelineStatus(status: string): status is PipelineStatus {
  return (PIPELINE_STATUSES as readonly string[]).includes(status)
}

/** 版本下拉展示用时间：草稿取 createdAt，其余取 publishedAt（回退 createdAt） */
export function configVersionSortTime(v: ConfigVersionSummary): string {
  return v.status === 'draft' ? v.createdAt : (v.publishedAt ?? v.createdAt)
}

export function configVersionTimeLabel(v: ConfigVersionSummary): string {
  return formatSkillVersionTime(configVersionSortTime(v))
}

/** 版本列表倒序：展示时间新→旧，同秒再按 versionNo / id */
export function sortConfigVersionsDesc(
  versions: ConfigVersionSummary[],
): ConfigVersionSummary[] {
  return [...versions].sort((a, b) => {
    const tb = Date.parse(configVersionSortTime(b)) || 0
    const ta = Date.parse(configVersionSortTime(a)) || 0
    if (tb !== ta) return tb - ta
    if (b.versionNo !== a.versionNo) return b.versionNo - a.versionNo
    return b.id - a.id
  })
}

export function resolveConfigVersionStatus(v: ConfigVersionSummary): ConfigVersionUiStatus {
  if (v.status === 'draft') return 'draft'
  if (v.status === 'pending_eval') return 'pending_eval'
  if (v.status === 'evaluating') return 'evaluating'
  if (v.status === 'eval_passed') return 'eval_passed'
  if (v.status === 'eval_failed') return 'eval_failed'
  if (v.active || v.status === 'active') return 'live'
  if (v.status === 'published') return v.active ? 'live' : 'inactive'
  return 'inactive'
}

export function configVersionStatusLabel(status: ConfigVersionUiStatus): string {
  if (status === 'live') return '生效'
  if (status === 'draft') return '草稿'
  if (status === 'pending_eval') return '待评测'
  if (status === 'evaluating') return '评测中'
  if (status === 'eval_passed') return '评测通过'
  if (status === 'eval_failed') return '评测失败'
  return '非生效'
}

export function configVersionStatusTagType(
  status: ConfigVersionUiStatus,
): 'success' | 'warning' | 'error' | 'info' | 'default' {
  if (status === 'live') return 'success'
  if (status === 'draft') return 'warning'
  if (status === 'pending_eval') return 'info'
  if (status === 'evaluating') return 'warning'
  if (status === 'eval_passed') return 'success'
  if (status === 'eval_failed') return 'error'
  return 'default'
}

export function configVersionSelectLabel(v: ConfigVersionSummary): string {
  const time = configVersionTimeLabel(v)
  const status = configVersionStatusLabel(resolveConfigVersionStatus(v))
  return `${time} · ${status}`
}

export function isDraftConfigVersion(v: ConfigVersionSummary): boolean {
  return v.status === 'draft'
}

export function canApplyConfigVersion(v: ConfigVersionSummary): boolean {
  return !isDraftConfigVersion(v)
}

export function canRevertConfigVersion(v: ConfigVersionSummary): boolean {
  return v.status === 'pending_eval' || v.status === 'eval_passed' || v.status === 'eval_failed'
}

/** 参数配置编辑区默认选中：展示时间最新一条 */
export function findNewestConfigVersion(
  versions: ConfigVersionSummary[],
): ConfigVersionSummary | null {
  return sortConfigVersionsDesc(versions)[0] ?? null
}

export function findPipelineVersion(versions: ConfigVersionSummary[]): ConfigVersionSummary | null {
  return versions.find((v) => isPipelineStatus(v.status)) ?? null
}

export function hasDraftInPipeline(versions: ConfigVersionSummary[]): boolean {
  return versions.some((v) => v.status === 'draft')
}

export function isPipelineLocked(versions: ConfigVersionSummary[]): boolean {
  return versions.some((v) => v.status === 'evaluating')
}

/** 当前选中版本是否处于评测中（只读，但可切换版本/导出） */
export function canShowMoreMenu(versions: ConfigVersionSummary[]): boolean {
  return versions.length > 0
}

export function isSelectedPipelineVersion(
  selected: ConfigVersionSummary | null,
  versions: ConfigVersionSummary[],
): boolean {
  if (!selected) return false
  const pipeline = findPipelineVersion(versions)
  return pipeline?.id === selected.id
}

export function canShowSaveDraft(
  selected: ConfigVersionSummary | null,
  versions: ConfigVersionSummary[],
): boolean {
  return !isPipelineLocked(versions) && selected?.status === 'draft'
}

export function canShowSubmitEval(
  selected: ConfigVersionSummary | null,
  versions: ConfigVersionSummary[],
): boolean {
  return !isPipelineLocked(versions)
    && selected?.status === 'draft'
    && isSelectedPipelineVersion(selected, versions)
}

export function canShowCopyToDraft(
  selected: ConfigVersionSummary | null,
  versions: ConfigVersionSummary[],
): boolean {
  if (!selected || isPipelineLocked(versions) || hasDraftInPipeline(versions)) return false
  if (findPipelineVersion(versions)) return false
  return selected.status === 'active' || selected.status === 'superseded' || selected.status === 'published'
}

export function canShowRevertToDraft(
  selected: ConfigVersionSummary | null,
  versions: ConfigVersionSummary[],
): boolean {
  if (!selected || isPipelineLocked(versions)) return false
  return canRevertConfigVersion(selected) && isSelectedPipelineVersion(selected, versions)
}

export function canShowActivate(
  selected: ConfigVersionSummary | null,
  versions: ConfigVersionSummary[],
): boolean {
  if (!selected || isPipelineLocked(versions)) return false
  return canActivateConfigVersion(selected, versions) && isSelectedPipelineVersion(selected, versions)
}

export function canEditConfigForm(
  selected: ConfigVersionSummary | null,
  versions: ConfigVersionSummary[],
): boolean {
  return canShowSaveDraft(selected, versions)
}

export function canActivateConfigVersion(v: ConfigVersionSummary, versions: ConfigVersionSummary[]): boolean {
  if (v.status !== 'eval_passed') return false
  const latestPassed = versions
    .filter((item) => item.status === 'eval_passed')
    .sort((a, b) => b.versionNo - a.versionNo)[0]
  return latestPassed?.id === v.id
}

export function buildAppliedConfigForVersion(ver: ConfigVersionSummary): KbAppliedConfig {
  if (isDraftConfigVersion(ver)) {
    throw new Error('草稿版本不可作为应用配置')
  }
  const label = configVersionSelectLabel(ver)
  if (ver.active || ver.status === 'active') {
    return { mode: 'published', versionId: ver.id, label }
  }
  return { mode: 'version', versionId: ver.id, label }
}

export function findDefaultAppliedVersion(versions: ConfigVersionSummary[]): ConfigVersionSummary | null {
  return versions.find((v) => v.active || v.status === 'active')
    ?? versions.find((v) => v.status === 'eval_passed')
    ?? versions.find((v) => canApplyConfigVersion(v))
    ?? null
}

export function canRunEvalForAppliedVersion(v: ConfigVersionSummary | null): boolean {
  if (!v || !canApplyConfigVersion(v)) return false
  return v.status !== 'evaluating'
}

/** 生效/非生效版本评测仅记录，不驱动 pipeline 状态流转 */
export function isBenchmarkEvalOnly(v: ConfigVersionSummary | null): boolean {
  if (!v) return false
  return v.active || v.status === 'active' || v.status === 'superseded' || v.status === 'published'
}

export function appliedConfigVersions(versions: ConfigVersionSummary[]): ConfigVersionSummary[] {
  return sortConfigVersionsDesc(versions.filter((v) => canApplyConfigVersion(v)))
}

function isEvalJobActiveStatus(status: string): boolean {
  return status === 'pending' || status === 'running'
}

/** 解析评测任务关联的配置版本（无 id 时回退当前生效版本） */
export function resolveVersionForEvalJob(
  job: { configVersionId: number | null; configVersionNo?: number | null },
  versions: ConfigVersionSummary[],
): ConfigVersionSummary | null {
  if (job.configVersionId != null) {
    return versions.find((v) => v.id === job.configVersionId) ?? null
  }
  return versions.find((v) => v.active || v.status === 'active') ?? null
}

/** 评测记录「配置版本」列：状态标签 + 时间版本号，样式对齐右上角应用配置 */
export function evalJobConfigVersionDisplay(
  job: { configVersionId: number | null; configVersionNo?: number | null; status: string },
  versions: ConfigVersionSummary[],
): {
  statusLabel: string
  tagType: 'success' | 'warning' | 'error' | 'info' | 'default'
  timeLabel: string
} {
  const ver = resolveVersionForEvalJob(job, versions)
  let status: ConfigVersionUiStatus
  if (isEvalJobActiveStatus(job.status)) {
    status = 'evaluating'
  } else if (ver) {
    status = resolveConfigVersionStatus(ver)
  } else {
    status = 'inactive'
  }
  return {
    statusLabel: configVersionStatusLabel(status),
    tagType: configVersionStatusTagType(status),
    timeLabel: ver ? configVersionTimeLabel(ver) : '—',
  }
}

/** 评测任务是否关联当前生效配置（生效配置不可一键应用参数建议） */
export function isEvalJobEvalFailedConfigVersion(
  job: { configVersionId: number | null; configVersionNo?: number | null },
  versions: ConfigVersionSummary[],
): boolean {
  const ver = resolveVersionForEvalJob(job, versions)
  return ver?.status === 'eval_failed'
}

/** 同一 configVersionId 下 jobId 最大（同 id 则 createdAt 更新）的记录 */
export function findLatestEvalJobForConfigVersion(
  jobs: EvalJobSummary[],
  configVersionId: number | null,
): EvalJobSummary | null {
  if (configVersionId == null) return null
  return jobs
    .filter((j) => j.configVersionId === configVersionId)
    .reduce<EvalJobSummary | null>((best, job) => {
      if (!best) return job
      if (job.jobId > best.jobId) return job
      if (job.jobId < best.jobId) return best
      return Date.parse(job.createdAt) > Date.parse(best.createdAt) ? job : best
    }, null)
}

export function isLatestEvalJobForConfigVersion(
  job: EvalJobSummary,
  jobs: EvalJobSummary[],
): boolean {
  if (job.configVersionId == null) return false
  const latest = findLatestEvalJobForConfigVersion(jobs, job.configVersionId)
  return latest?.jobId === job.jobId
}

export function isEvalJobGateFailed(job: EvalJobSummary): boolean {
  return job.status === 'done' && job.passedGate === false
}

/** 同一配置版本仅最新一条评测失败记录可生成/应用优化建议 */
export function canShowEvalSuggestActions(
  job: EvalJobSummary | undefined,
  jobs: EvalJobSummary[],
  versions: ConfigVersionSummary[],
): boolean {
  if (!job) return false
  if (!isLatestEvalJobForConfigVersion(job, jobs)) return false
  if (!isEvalJobGateFailed(job)) return false
  return isEvalJobEvalFailedConfigVersion(job, versions)
}
