import { beforeEach, describe, expect, it } from 'vitest'
import { PLAN_COMPARE_MIN, usePlanNodeDrawer } from './usePlanNodeDrawer'
import {
  CHAT_CONTENT_MIN_WIDTH,
  DRAWER_MIN_WIDTH,
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

describe('resolveSandboxDrawerMaxWidth', () => {
  it('single mode reserves chat content min', () => {
    expect(resolveSandboxDrawerMaxWidth(1400, false)).toBe(1400 - CHAT_CONTENT_MIN_WIDTH)
  })

  it('compare mode reserves plan compare min', () => {
    expect(resolveSandboxDrawerMaxWidth(1200, true)).toBe(1200 - PLAN_COMPARE_MIN)
    expect(resolveSandboxDrawerMaxWidth(1200, true)).toBeGreaterThanOrEqual(DRAWER_MIN_WIDTH)
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
