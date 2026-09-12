import { reactive } from 'vue'

/**
 * 右侧抽屉统一堆栈（节点/子agent 抽屉 + 工作区抽屉）：
 * - 面板永远以浮层覆盖正文，不挤占布局
 * - 容量 3，先进先出：第四个面板打开时淘汰最早打开者
 *   （worker→子agent 为节点面板内部层级，加工作区共 2 类面板，容量 3 兜住三层并存）
 * - 栈位决定 z-index（底 200 起、每层 +10），关闭顶层即露出下层（状态无损）
 */
export type RightDrawerId = 'plan' | 'sandbox'

const MAX_VISIBLE = 3

export const rightDrawerStack = reactive({
  panels: [] as RightDrawerId[],
})

const closeHandlers: Partial<Record<RightDrawerId, () => void>> = {}

/** 各面板 composable 注册自己的关闭函数（避免相互 import 成环） */
export function registerRightDrawerClose(id: RightDrawerId, close: () => void) {
  closeHandlers[id] = close
}

/** 入栈：返回被淘汰的面板（已触发其关闭） */
export function pushRightDrawer(id: RightDrawerId): RightDrawerId[] {
  const evicted: RightDrawerId[] = []
  if (!rightDrawerStack.panels.includes(id)) {
    rightDrawerStack.panels.push(id)
    while (rightDrawerStack.panels.length > MAX_VISIBLE) {
      const oldest = rightDrawerStack.panels.shift()!
      evicted.push(oldest)
    }
  }
  for (const e of evicted) closeHandlers[e]?.()
  return evicted
}

export function removeRightDrawer(id: RightDrawerId) {
  const i = rightDrawerStack.panels.indexOf(id)
  if (i >= 0) rightDrawerStack.panels.splice(i, 1)
}

/** 栈位 z-index：底 200 起、每层 +10（不在栈中给最低，避免关闭动画闪层） */
export function rightDrawerZIndex(id: RightDrawerId): number {
  const i = rightDrawerStack.panels.indexOf(id)
  return i < 0 ? 200 : 200 + i * 10
}
