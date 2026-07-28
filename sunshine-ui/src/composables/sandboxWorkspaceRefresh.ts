import { reactive } from 'vue'

export type SandboxWorkspaceRefreshScope = 'workspace' | 'skills'

/** 沙箱工作区 / 路径补全刷新信号（SSE 工具终态、Skill 挂载、发送 /skill） */
export const sandboxWorkspaceRefresh = reactive({
  tick: 0,
  conversationId: null as string | null,
  scope: 'workspace' as SandboxWorkspaceRefreshScope,
})

/** 密集 write 合并刷新，避免每文件一轮 list/content */
const DEBOUNCE_MS = 400

type Pending = {
  conversationId: string
  scopes: Set<SandboxWorkspaceRefreshScope>
  timer: ReturnType<typeof setTimeout>
}

const pendingByConv = new Map<string, Pending>()

function flushPending(conversationId: string) {
  const pending = pendingByConv.get(conversationId)
  if (!pending) return
  pendingByConv.delete(conversationId)
  clearTimeout(pending.timer)
  sandboxWorkspaceRefresh.conversationId = pending.conversationId
  for (const scope of pending.scopes) {
    sandboxWorkspaceRefresh.scope = scope
    sandboxWorkspaceRefresh.tick += 1
  }
}

/**
 * 请求刷新沙箱抽屉文件树。同会话短时多次调用会合并；不同 scope 各 tick 一次。
 * @param immediate 跳过防抖（手动刷新 / 挂载技能等）
 */
export function requestSandboxWorkspaceRefresh(
  conversationId: string,
  scope: SandboxWorkspaceRefreshScope,
  immediate = false,
) {
  const cid = conversationId.trim()
  if (!cid) return
  if (immediate) {
    const existing = pendingByConv.get(cid)
    if (existing) {
      clearTimeout(existing.timer)
      pendingByConv.delete(cid)
      existing.scopes.add(scope)
      sandboxWorkspaceRefresh.conversationId = cid
      for (const s of existing.scopes) {
        sandboxWorkspaceRefresh.scope = s
        sandboxWorkspaceRefresh.tick += 1
      }
      return
    }
    sandboxWorkspaceRefresh.conversationId = cid
    sandboxWorkspaceRefresh.scope = scope
    sandboxWorkspaceRefresh.tick += 1
    return
  }
  let pending = pendingByConv.get(cid)
  if (!pending) {
    pending = {
      conversationId: cid,
      scopes: new Set(),
      timer: setTimeout(() => flushPending(cid), DEBOUNCE_MS),
    }
    pendingByConv.set(cid, pending)
  } else {
    clearTimeout(pending.timer)
    pending.timer = setTimeout(() => flushPending(cid), DEBOUNCE_MS)
  }
  pending.scopes.add(scope)
}

/** 测试 / 卸载：冲刷或丢弃待合并刷新 */
export function flushSandboxWorkspaceRefresh(conversationId?: string) {
  if (conversationId) {
    flushPending(conversationId.trim())
    return
  }
  for (const cid of [...pendingByConv.keys()]) {
    flushPending(cid)
  }
}

export function clearSandboxWorkspaceRefreshPending() {
  for (const pending of pendingByConv.values()) {
    clearTimeout(pending.timer)
  }
  pendingByConv.clear()
}
