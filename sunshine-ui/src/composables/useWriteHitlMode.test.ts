import { describe, expect, it } from 'vitest'
import { getWriteHitlMode, setWriteHitlMode } from '../composables/useWriteHitlMode'

describe('useWriteHitlMode per conversation', () => {
  it('defaults to never and isolates by conversationId', () => {
    expect(getWriteHitlMode('c1')).toBe('never')
    setWriteHitlMode('c1', 'smart')
    setWriteHitlMode('c2', 'always')
    expect(getWriteHitlMode('c1')).toBe('smart')
    expect(getWriteHitlMode('c2')).toBe('always')
    expect(getWriteHitlMode('c3')).toBe('never')
  })
})
