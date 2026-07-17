import { beforeEach, describe, expect, it } from 'vitest'
import {
  CHAT_CONTENT_MIN_WIDTH,
  DRAWER_MIN_WIDTH as PLAN_MIN,
  PANE_MIN_WIDTH,
  resolvePlanDrawerMaxWidth,
  SANDBOX_DRAWER_MIN_WIDTH,
  splitRightDrawerBudget,
  usePlanNodeDrawer,
} from './usePlanNodeDrawer'
import {
  DRAWER_MIN_WIDTH as SANDBOX_MIN,
  resolveSandboxDrawerMaxWidth,
  useSandboxWorkspaceDrawer,
} from './useSandboxWorkspaceDrawer'
import type { DagNodeView } from '../utils/planGraph'

const sampleNode: DagNodeView = {
  id: 'n1',
  label: 'agent',
  type: 'agent',
  status: 'done',
}

describe('pane mins', () => {
  it('chat / plan / sandbox share the same min', () => {
    expect(CHAT_CONTENT_MIN_WIDTH).toBe(PANE_MIN_WIDTH)
    expect(PLAN_MIN).toBe(PANE_MIN_WIDTH)
    expect(SANDBOX_MIN).toBe(PANE_MIN_WIDTH)
    expect(SANDBOX_DRAWER_MIN_WIDTH).toBe(PANE_MIN_WIDTH)
  })
})

describe('resolveSandboxDrawerMaxWidth', () => {
  it('single mode reserves chat content min', () => {
    expect(resolveSandboxDrawerMaxWidth(1400, false)).toBe(1400 - CHAT_CONTENT_MIN_WIDTH)
  })

  it('both open reserves chat + plan width', () => {
    expect(resolveSandboxDrawerMaxWidth(1400, true, 420)).toBe(1400 - CHAT_CONTENT_MIN_WIDTH - 420)
    expect(resolveSandboxDrawerMaxWidth(1400, true, 420)).toBeGreaterThanOrEqual(SANDBOX_MIN)
  })
})

describe('resolvePlanDrawerMaxWidth', () => {
  it('both open reserves chat + sandbox width', () => {
    expect(resolvePlanDrawerMaxWidth(1400, true, 520)).toBe(
      1400 - CHAT_CONTENT_MIN_WIDTH - 520,
    )
  })
})

describe('splitRightDrawerBudget', () => {
  it('keeps plan+sandbox sum and clamps mins', () => {
    const budget = 1000
    const a = splitRightDrawerBudget(budget, 700, PLAN_MIN, SANDBOX_MIN)
    expect(a.plan + a.sandbox).toBe(budget)
    expect(a.sandbox).toBe(580) // 700 → plan 300 < 420 → sandbox = 580
    expect(a.plan).toBe(PLAN_MIN)

    const b = splitRightDrawerBudget(budget, 400, PLAN_MIN, SANDBOX_MIN)
    expect(b.sandbox).toBe(SANDBOX_MIN)
    expect(b.plan).toBe(budget - SANDBOX_MIN)

    const c = splitRightDrawerBudget(budget, 550, PLAN_MIN, SANDBOX_MIN)
    expect(c.sandbox).toBe(550)
    expect(c.plan).toBe(450)
  })
})

describe('sandbox / plan drawer coexistence', () => {
  beforeEach(() => {
    usePlanNodeDrawer().close()
    useSandboxWorkspaceDrawer().close()
  })

  it('opening sandbox does not close plan drawer', () => {
    const plan = usePlanNodeDrawer()
    const sb = useSandboxWorkspaceDrawer()
    plan.open({ planId: 'p1', node: sampleNode })
    sb.open({ conversationId: 'c1' })
    expect(plan.state.open).toBe(true)
    expect(plan.state.node?.id).toBe('n1')
    expect(sb.state.open).toBe(true)
    expect(sb.compareMode.value).toBe(true)
  })

  it('opening plan does not close sandbox drawer', () => {
    const plan = usePlanNodeDrawer()
    const sb = useSandboxWorkspaceDrawer()
    sb.open({ conversationId: 'c1' })
    plan.open({ planId: 'p1', node: sampleNode })
    expect(sb.state.open).toBe(true)
    expect(plan.state.open).toBe(true)
    expect(sb.compareMode.value).toBe(true)
  })
})
