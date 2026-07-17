import { reactive } from 'vue'

/**
 * Plan ↔ Sandbox 抽屉布局桥接（避免 composable 循环依赖）。
 * 双开时：Chat | 节点抽屉 | 沙箱（不隐藏执行计划/DAG）。
 */
export const sandboxDrawerLayout = reactive({
  open: false,
  width: 520,
})

export function setSandboxDrawerLayout(open: boolean, width: number) {
  sandboxDrawerLayout.open = open
  sandboxDrawerLayout.width = width
}

export function isSandboxDrawerOpen() {
  return sandboxDrawerLayout.open
}

export function getSandboxDrawerWidth() {
  return sandboxDrawerLayout.width
}

/** @deprecated 对照不再互关 */
export function setSandboxWorkspaceCloser(_fn: (() => void) | null) {
  /* no-op */
}

/** @deprecated */
export function closeSandboxWorkspaceDrawerIfOpen() {
  /* no-op */
}
