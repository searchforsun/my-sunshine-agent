/** TaskBoard 一级项按 dependsOn 分波（无依赖边；同波可并排） */
import type { TaskBoardItemView } from './processingSteps'

/**
 * 将一级 checklist 按 dependsOn 拓扑分层。
 * - 无 dependsOn / 依赖已不在剩余集合 → 进入当前波
 * - 环或不可解析依赖：剩余项整包落入末波，避免死循环
 */
export function groupTaskBoardWaves(items: TaskBoardItemView[]): TaskBoardItemView[][] {
  if (!items.length) return []
  const byId = new Map(items.map(item => [item.id, item]))
  const remaining = new Set(items.map(item => item.id))
  const waves: TaskBoardItemView[][] = []
  while (remaining.size > 0) {
    const waveIds = [...remaining].filter((id) => {
      const deps = byId.get(id)?.dependsOn ?? []
      return deps.every(dep => !remaining.has(dep))
    })
    if (waveIds.length === 0) {
      waves.push([...remaining].map(id => byId.get(id)!))
      break
    }
    waves.push(waveIds.map(id => byId.get(id)!))
    for (const id of waveIds) remaining.delete(id)
  }
  return waves
}

/** 一级下是否渲染二级 todolist（有 items 才展示） */
export function hasSecondaryTodos(item: TaskBoardItemView): boolean {
  return (item.secondary?.length ?? 0) > 0
}
