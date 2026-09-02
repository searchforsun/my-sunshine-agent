import type { L1WindowRow } from '../../api/contextAdmin'

/** L2 kind 展示文案；kind 集合 SSOT = orchestrator `context/l2/ContextKind`，两处须同步。 */
export const KIND_META: Record<string, { label: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  profile: { label: '画像', type: 'info' },
  preference: { label: '偏好', type: 'success' },
  goal: { label: '目标', type: 'info' },
  agreement: { label: '约定', type: 'warning' },
  constraint: { label: '限制', type: 'error' },
  fact: { label: '事实', type: 'default' },
  decision: { label: '方案', type: 'success' },
  process_note: { label: '过程记录', type: 'default' },
  todo: { label: '待办', type: 'warning' },
}

export const STATUS_LABEL: Record<string, string> = {
  active: '生效',
  superseded: '已覆盖',
  void: '已作废',
  conflict: '矛盾',
}

export function kindMeta(kind: string) {
  return KIND_META[kind] || { label: kind, type: 'default' as const }
}

export function statusLabel(status: string) {
  return STATUS_LABEL[status] || status
}

export function statusType(status: string): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'active') return 'success'
  if (status === 'superseded' || status === 'conflict') return 'warning'
  if (status === 'void') return 'error'
  return 'default'
}

export function bandLabel(band: string) {
  if (band === 'near') return '近'
  if (band === 'mid') return '中'
  if (band === 'far') return '远'
  return band
}

export function rowTag(row: L1WindowRow) {
  if (row.band === 'far') return '远'
  return `${bandLabel(row.band)} #${row.index}`
}

export function formatTime(iso?: string | null) {
  if (!iso) return '—'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return iso
  return new Date(t).toLocaleString()
}

export function l3RoleLabel(_role?: string) {
  // v28：对话 L3 面板仅展示 semantic 摘要层，原文 user/assistant 角色已无意义，统一标记为 Chunk
  return 'Chunk'
}
