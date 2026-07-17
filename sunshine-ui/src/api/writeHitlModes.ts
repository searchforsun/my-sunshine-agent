/** 沙箱写操作 HITL 跳过模式 — 本会话，默认 never */
export type WriteHitlMode = 'never' | 'always' | 'smart'

export interface WriteHitlModeOption {
  value: WriteHitlMode
  label: string
  description: string
}

export const WRITE_HITL_MODE_OPTIONS: WriteHitlModeOption[] = [
  { value: 'never', label: '永不跳过', description: '写操作均需确认' },
  { value: 'always', label: '总是跳过', description: '跳过全部沙箱写确认' },
  { value: 'smart', label: '智能跳过', description: '跳过非危险写操作' },
]

export function isWriteHitlMode(v: unknown): v is WriteHitlMode {
  return v === 'never' || v === 'always' || v === 'smart'
}

export function findWriteHitlModeOption(value: WriteHitlMode): WriteHitlModeOption {
  return WRITE_HITL_MODE_OPTIONS.find(o => o.value === value) ?? WRITE_HITL_MODE_OPTIONS[0]
}
