/** TaskBoard 一级项按 dependsOn 分波（无依赖边；同波可并排） */
import type { TaskBoardItemView } from './processingSteps'

/** 一级下是否渲染二级 todolist（有 items 才展示） */
export function hasSecondaryTodos(item: TaskBoardItemView): boolean {
  return (item.secondary?.length ?? 0) > 0
}
