/** 避免 usePlanNodeDrawer ↔ useSandboxWorkspaceDrawer 循环依赖 */
let sandboxCloser: (() => void) | null = null

export function setSandboxWorkspaceCloser(fn: (() => void) | null) {
  sandboxCloser = fn
}

export function closeSandboxWorkspaceDrawerIfOpen() {
  sandboxCloser?.()
}
