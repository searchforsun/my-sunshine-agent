/**
 * 删除工作区后的本地会话清理决策：从列表移除归属会话，并在必要时切到最新剩余会话。
 */

export interface WorkspacePurgeableConversation {
  id: string
  workspaceId?: string | null
  updatedAt: number
}

export interface WorkspacePurgeResult<T extends WorkspacePurgeableConversation> {
  remaining: T[]
  removedIds: string[]
  /** 清理后应展示的 currentId；无需切换时原样返回 */
  nextCurrentId: string | null
  clearPending: boolean
  didSwitch: boolean
}

export function purgeConversationsForWorkspace<T extends WorkspacePurgeableConversation>(
  conversations: T[],
  workspaceId: string,
  currentId: string | null,
  pendingWorkspaceId: string | null,
): WorkspacePurgeResult<T> {
  const clearPending = pendingWorkspaceId === workspaceId
  const removedIds = conversations
    .filter(c => c.workspaceId === workspaceId)
    .map(c => c.id)
  const remaining = conversations.filter(c => c.workspaceId !== workspaceId)
  const currentBelongsToWorkspace = currentId != null && removedIds.includes(currentId)
  // 当前会话属被删工作区，或新任务页正挂在该工作区上 → 切到最新剩余会话
  const didSwitch = currentBelongsToWorkspace || (clearPending && currentId == null)
  if (!didSwitch) {
    return { remaining, removedIds, nextCurrentId: currentId, clearPending, didSwitch: false }
  }
  const latest = [...remaining].sort((a, b) => b.updatedAt - a.updatedAt)[0]
  return {
    remaining,
    removedIds,
    nextCurrentId: latest?.id ?? null,
    clearPending,
    didSwitch: true,
  }
}
