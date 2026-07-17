/**
 * @deprecated 对照模式已取消抽屉硬互斥；保留空实现以免旧调用编译失败。
 */
export function setSandboxWorkspaceCloser(_fn: (() => void) | null) {
  /* no-op */
}

/** @deprecated */
export function closeSandboxWorkspaceDrawerIfOpen() {
  /* no-op */
}
