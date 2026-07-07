import type { SkillEntry, SkillVersion } from '../../api/skills'
import { formatSkillVersionTime } from '../formatSkillVersionTime'

/** Skill 生命周期阶段 — 驱动主操作与引导文案 */
export type SkillPhase = 'setup' | 'draft' | 'live' | 'history'

export type VersionStatus = 'live' | 'inactive' | 'draft'

export function resolveVersionStatus(v: SkillVersion, activeNum: number | null): VersionStatus {
  if (v.status === 'draft') return 'draft'
  if (v.version === activeNum) return 'live'
  return 'inactive'
}

export function versionStatusLabel(status: VersionStatus): string {
  if (status === 'live') return '生效'
  if (status === 'draft') return '草稿'
  return '非生效'
}

export function versionStatusTagType(status: VersionStatus): 'success' | 'warning' | 'default' {
  if (status === 'live') return 'success'
  if (status === 'draft') return 'warning'
  return 'default'
}

/** 列表卡片：当前 active 版本是否已发布生效 */
export function skillHasPublishedVersion(skill: SkillEntry): boolean {
  if (skill.activeVersionPublished === true) return true
  if (skill.activeVersionPublished === false) return false
  return skill.enabled
}

/** 仅阻止「未发布时开启」；已开启时须允许关闭 */
export function isSkillSwitchDisabled(skill: SkillEntry): boolean {
  if (skill.enabled) return false
  return !skillHasPublishedVersion(skill)
}

export function versionOptionLabel(v: SkillVersion): string {
  return formatSkillVersionTime(v.createdAt) || (v.storagePath ? '—' : '待上传')
}

export function listCardActiveVersionLine(skill: SkillEntry): string {
  if (!skillHasPublishedVersion(skill)) return '生效版本：未发布'
  return `生效版本：${formatSkillVersionTime(skill.activeVersionCreatedAt)}`
}

export function listCardMaintainer(skill: SkillEntry): string | null {
  const name = skill.activeVersionMaintainerName
  return name ? `维护人：${name}` : null
}
