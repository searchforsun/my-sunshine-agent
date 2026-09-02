import { describe, expect, it } from 'vitest'
import { purgeConversationsForWorkspace } from './conversationWorkspacePurge'

function conv(id: string, workspaceId: string | null, updatedAt: number) {
  return { id, workspaceId, updatedAt }
}

describe('purgeConversationsForWorkspace', () => {
  it('删除工作区后移除归属会话，并切到最新剩余会话', () => {
    const list = [
      conv('task-old', 'ws-a', 100),
      conv('task-cur', 'ws-a', 200),
      conv('chat-new', null, 300),
      conv('task-other', 'ws-b', 250),
    ]
    const result = purgeConversationsForWorkspace(list, 'ws-a', 'task-cur', null)
    expect(result.removedIds).toEqual(['task-old', 'task-cur'])
    expect(result.remaining.map(c => c.id)).toEqual(['chat-new', 'task-other'])
    expect(result.didSwitch).toBe(true)
    expect(result.nextCurrentId).toBe('chat-new')
    expect(result.clearPending).toBe(false)
  })

  it('当前会话不属于被删工作区时不切换', () => {
    const list = [
      conv('task-a', 'ws-a', 100),
      conv('chat-keep', null, 50),
    ]
    const result = purgeConversationsForWorkspace(list, 'ws-a', 'chat-keep', null)
    expect(result.removedIds).toEqual(['task-a'])
    expect(result.nextCurrentId).toBe('chat-keep')
    expect(result.didSwitch).toBe(false)
  })

  it('新任务挂在被删工作区时清除 pending 并切到最新会话', () => {
    const list = [
      conv('chat-1', null, 10),
      conv('chat-2', null, 40),
    ]
    const result = purgeConversationsForWorkspace(list, 'ws-gone', null, 'ws-gone')
    expect(result.clearPending).toBe(true)
    expect(result.didSwitch).toBe(true)
    expect(result.nextCurrentId).toBe('chat-2')
  })

  it('无剩余会话时 nextCurrentId 为 null', () => {
    const list = [conv('only', 'ws-a', 1)]
    const result = purgeConversationsForWorkspace(list, 'ws-a', 'only', null)
    expect(result.remaining).toEqual([])
    expect(result.nextCurrentId).toBeNull()
    expect(result.didSwitch).toBe(true)
  })
})
