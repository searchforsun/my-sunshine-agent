import { reactive } from 'vue'

export type SandboxWorkspaceRefreshScope = 'workspace' | 'skills'

/** 沙箱工作区 / 路径补全刷新信号（SSE 工具终态、Skill 挂载、发送 @skill） */
export const sandboxWorkspaceRefresh = reactive({
  tick: 0,
  conversationId: null as string | null,
  scope: 'workspace' as SandboxWorkspaceRefreshScope,
})

export function requestSandboxWorkspaceRefresh(
  conversationId: string,
  scope: SandboxWorkspaceRefreshScope,
) {
  const cid = conversationId.trim()
  if (!cid) return
  sandboxWorkspaceRefresh.conversationId = cid
  sandboxWorkspaceRefresh.scope = scope
  sandboxWorkspaceRefresh.tick += 1
}
