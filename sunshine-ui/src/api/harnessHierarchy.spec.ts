import { describe, expect, it } from 'vitest'
import {
  isHarnessPlanStep,
  isWorkerStep,
} from './harnessHierarchy'

describe('harnessHierarchy', () => {
  it('isWorkerStep / isHarnessPlanStep', () => {
    expect(isWorkerStep({ id: 'worker-t1', phase: 'worker' })).toBe(true)
    expect(isWorkerStep({ id: 'worker-x', phase: 'tool' })).toBe(true)
    expect(isWorkerStep({ id: 'think', phase: 'think' })).toBe(false)
    expect(isHarnessPlanStep({ id: 'plan', phase: 'plan' })).toBe(true)
    expect(isHarnessPlanStep({ id: 'plan-R2', phase: 'plan' })).toBe(true)
    expect(isHarnessPlanStep({ id: 'plan-R2', phase: 'think' })).toBe(true)
    expect(isHarnessPlanStep({ id: 'intent', phase: 'intent' })).toBe(false)
  })
})
