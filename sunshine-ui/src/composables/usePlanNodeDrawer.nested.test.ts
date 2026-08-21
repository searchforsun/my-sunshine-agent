import { beforeEach, describe, expect, it } from 'vitest'
import { usePlanNodeDrawer } from './usePlanNodeDrawer'
import type { DagNodeView } from '../utils/planGraph'

const sampleNode = (id: string): DagNodeView => ({
  id,
  label: `节点 ${id}`,
  type: 'worker',
  status: 'done',
})

describe('usePlanNodeDrawer 层级栈', () => {
  beforeEach(() => {
    usePlanNodeDrawer().close()
  })

  it('首次打开重置为单层', () => {
    const drawer = usePlanNodeDrawer()
    drawer.open({ planId: 'worker-1', node: sampleNode('worker-1') })
    expect(drawer.state.open).toBe(true)
    expect(drawer.state.node?.id).toBe('worker-1')
    expect(drawer.depth.value).toBe(1)
  })

  it('抽屉内下钻 push 入栈，返回后恢复父层', () => {
    const drawer = usePlanNodeDrawer()
    drawer.open({ planId: 'worker-1', node: sampleNode('worker-1') })
    drawer.open({ planId: 'subagent-a', node: sampleNode('subagent-a') }, { push: true })
    expect(drawer.depth.value).toBe(2)
    expect(drawer.state.node?.id).toBe('subagent-a')

    drawer.goBack()
    expect(drawer.depth.value).toBe(1)
    expect(drawer.state.node?.id).toBe('worker-1')
    expect(drawer.state.open).toBe(true)
  })

  it('多层下钻逐层返回，最顶层才关闭', () => {
    const drawer = usePlanNodeDrawer()
    drawer.open({ planId: 'worker-1', node: sampleNode('worker-1') })
    drawer.open({ planId: 'subagent-a', node: sampleNode('subagent-a') }, { push: true })
    drawer.open({ planId: 'subagent-b', node: sampleNode('subagent-b') }, { push: true })
    expect(drawer.depth.value).toBe(3)
    expect(drawer.state.node?.id).toBe('subagent-b')

    drawer.goBack()
    expect(drawer.depth.value).toBe(2)
    expect(drawer.state.node?.id).toBe('subagent-a')

    drawer.goBack()
    expect(drawer.depth.value).toBe(1)
    expect(drawer.state.node?.id).toBe('worker-1')

    drawer.goBack()
    expect(drawer.state.open).toBe(false)
    expect(drawer.depth.value).toBe(0)
  })

  it('同一 planId 重复下钻替换而非重复入栈', () => {
    const drawer = usePlanNodeDrawer()
    drawer.open({ planId: 'worker-1', node: sampleNode('worker-1') })
    drawer.open({ planId: 'subagent-a', node: sampleNode('subagent-a') }, { push: true })
    drawer.open({ planId: 'subagent-a', node: sampleNode('subagent-a') }, { push: true })
    expect(drawer.depth.value).toBe(2)
  })

  it('主时间线平级打开重置为单层', () => {
    const drawer = usePlanNodeDrawer()
    drawer.open({ planId: 'worker-1', node: sampleNode('worker-1') })
    drawer.open({ planId: 'subagent-a', node: sampleNode('subagent-a') }, { push: true })
    drawer.open({ planId: 'worker-2', node: sampleNode('worker-2') })
    expect(drawer.depth.value).toBe(1)
    expect(drawer.state.node?.id).toBe('worker-2')
  })

  it('关闭后重新打开从新栈开始', () => {
    const drawer = usePlanNodeDrawer()
    drawer.open({ planId: 'worker-1', node: sampleNode('worker-1') })
    drawer.open({ planId: 'subagent-a', node: sampleNode('subagent-a') }, { push: true })
    drawer.close()
    expect(drawer.state.open).toBe(false)
    expect(drawer.depth.value).toBe(0)

    drawer.open({ planId: 'worker-2', node: sampleNode('worker-2') })
    expect(drawer.depth.value).toBe(1)
    expect(drawer.state.node?.id).toBe('worker-2')
  })
})
