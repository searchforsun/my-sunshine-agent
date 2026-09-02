import { describe, expect, it } from 'vitest'
import { formatDateTimeLocal, formatSkillVersionTime } from './formatSkillVersionTime'
import { formatEvalTime } from './evalConstants'

describe('local datetime formatters', () => {
  it('formatSkillVersionTime converts true UTC Instant to local wall clock', () => {
    // 18:35 CST = 10:35 UTC
    const iso = '2026-07-21T10:35:22Z'
    const d = new Date(iso)
    const pad = (n: number) => String(n).padStart(2, '0')
    const expected = `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    expect(formatSkillVersionTime(iso)).toBe(expected)
    // 在东八区应显示 18:35，而不是把错误 Instant 18:35Z 再 +8 成 02:35
    if (d.getTimezoneOffset() === -480) {
      expect(formatSkillVersionTime(iso)).toBe('2026/07/21 18:35:22')
      expect(formatSkillVersionTime('2026-07-21T18:35:22Z')).toBe('2026/07/22 02:35:22')
    }
  })

  it('formatEvalTime / formatDateTimeLocal share local conversion', () => {
    const iso = '2026-07-21T10:04:08Z'
    expect(formatEvalTime(iso)).toBe(formatDateTimeLocal(iso))
    expect(formatEvalTime(null)).toBe('—')
  })
})
