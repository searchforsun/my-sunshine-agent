import { useRoute, useRouter } from 'vue-router'

export type ContextTab = 'l1' | 'l2' | 'l3'

const VALID_TABS = new Set<string>(['l1', 'l2', 'l3'])

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

/** 上下文页 URL：tenant / user / conv / tab，刷新后恢复；缺省由页面选第一项 */
export function useContextRouteState() {
  const route = useRoute()
  const router = useRouter()

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

  function syncQuery(state: {
    tenant: string | null
    user: string | null
    conv: string | null
    tab: ContextTab
  }) {
    const next: Record<string, string> = {}
    if (state.tenant) next.tenant = state.tenant
    if (state.user) next.user = state.user
    if (state.conv) next.conv = state.conv
    if (state.tab) next.tab = state.tab
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readTenant, readUser, readConv, readTab, syncQuery }
}
