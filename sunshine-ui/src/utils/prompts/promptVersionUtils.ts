import type { PromptVersionItem } from '../../api/prompts'
import type { PromptsTab } from '../../composables/usePromptsRouteState'

export type PromptVersionStatus = 'live' | 'inactive' | 'draft'

export function resolvePromptVersionStatus(
  v: PromptVersionItem,
  activeNum: number | null,
): PromptVersionStatus {
  if (v.status === 'draft') return 'draft'
  if (v.version === activeNum) return 'live'
  return 'inactive'
}

export function versionStatusLabel(status: PromptVersionStatus): string {
  if (status === 'live') return '生效'
  if (status === 'draft') return '草稿'
  return '非生效'
}

export function versionStatusTagType(status: PromptVersionStatus): 'success' | 'warning' | 'default' {
  if (status === 'live') return 'success'
  if (status === 'draft') return 'warning'
  return 'default'
}

export function tabForKind(kind: string): PromptsTab {
  if (kind === 'routing-rule') return 'routing'
  return 'system'
}
