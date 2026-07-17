import { formatSkillVersionTime } from '../formatSkillVersionTime'
import type { WorkflowEntry, WorkflowVersion } from '../../api/workflows'

export type WorkflowPhase = 'setup' | 'draft' | 'live' | 'history'
export type VersionStatus = 'draft' | 'published' | 'active'

export function resolveVersionStatus(
  version: WorkflowVersion | null | undefined,
  activeVersion: number | null | undefined,
): VersionStatus {
  if (!version) return 'draft'
  if (version.version === activeVersion && version.status === 'published') return 'active'
  return version.status === 'published' ? 'published' : 'draft'
}

export function versionStatusLabel(status: VersionStatus): string {
  switch (status) {
    case 'active': return '生效'
    case 'published': return '已发布'
    default: return '草稿'
  }
}

export function versionStatusTagType(status: VersionStatus): 'success' | 'warning' | 'default' {
  switch (status) {
    case 'active': return 'success'
    case 'published': return 'default'
    default: return 'warning'
  }
}

export function workflowHasPublishedVersion(entry: WorkflowEntry | null | undefined): boolean {
  return !!entry?.activeVersionPublished && (entry.activeVersion ?? 0) > 0
}

export function isWorkflowSwitchDisabled(entry: WorkflowEntry | null | undefined): boolean {
  return !workflowHasPublishedVersion(entry)
}

export function versionOptionLabel(version: WorkflowVersion): string {
  return formatSkillVersionTime(version.createdAt)
}

export function listCardActiveVersionLine(entry: WorkflowEntry): string {
  if (!entry.activeVersion || entry.activeVersion <= 0) return '未发布'
  const time = formatSkillVersionTime(entry.activeVersionCreatedAt)
  return `生效 ${time}`
}
