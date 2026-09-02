import { useRoute, useRouter } from 'vue-router'

export type ContextTab = 'l1' | 'l2' | 'l3'
export type ContextKindTab = 'chat' | 'task'
/** 任务分层上下文 tab（W0 工作区 / T0 任务进度 / H1 计划笔记本 / L3 任务检索） */
export type ContextTaskTab = 'w0' | 't0' | 'h1' | 'l3'

const VALID_TABS = new Set<string>(['l1', 'l2', 'l3'])
const VALID_TASK_TABS = new Set<string>(['w0', 't0', 'h1', 'l3'])

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

/** 上下文页 URL：kind / tenant / user / conv / tab(chat) / ttab(task)，刷新后恢复；缺省由页面选第一项 */
export function useContextRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readKind(): ContextKindTab {
    const kind = queryString(route.query.kind)
    return kind === 'task' ? 'task' : 'chat'
  }

  function readTenant(): string | null {
    return queryString(route.query.tenant)
  }

  function readUser(): string | null {
    return queryString(route.query.user)
  }

  function readConv(): string | null {
    return queryString(route.query.conv)
  }

  function readTab(): ContextTab {
    const tab = queryString(route.query.tab)
    if (tab && VALID_TABS.has(tab)) return tab as ContextTab
    return 'l1'
  }

  function readTaskTab(): ContextTaskTab {
    const tab = queryString(route.query.ttab)
    if (tab && VALID_TASK_TABS.has(tab)) return tab as ContextTaskTab
    return 'w0'
  }

  function syncQuery(state: {
    kind: ContextKindTab
    tenant: string | null
    user: string | null
    conv: string | null
    tab: ContextTab
    taskTab: ContextTaskTab
  }) {
    const next: Record<string, string> = {}
    if (state.kind) next.kind = state.kind
    if (state.tenant) next.tenant = state.tenant
    if (state.user) next.user = state.user
    if (state.conv) next.conv = state.conv
    if (state.kind === 'chat' && state.tab) next.tab = state.tab
    if (state.kind === 'task' && state.taskTab) next.ttab = state.taskTab
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readKind, readTenant, readUser, readConv, readTab, readTaskTab, syncQuery }
}
