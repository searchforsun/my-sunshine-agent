/**
 * 悬浮任务板：判定目标元素是否完全滚出滚动容器视口。
 * 运行期间 todolist 不在视口内时，ChatView 在输入框上方悬浮展示任务板。
 */

/** 元素与滚动容器是否有可见交集（任一像素可见即视为可见） */
export function isElVisibleInRoot(el: HTMLElement, root: HTMLElement): boolean {
  const elRect = el.getBoundingClientRect()
  const rootRect = root.getBoundingClientRect()
  return elRect.bottom > rootRect.top && elRect.top < rootRect.bottom
}
