import { useRoute, useRouter } from 'vue-router'

export type ToolsTab = 'sdk' | 'mcp' | 'platform' | 'toolset'

const VALID_TABS = new Set<string>(['sdk', 'mcp', 'platform', 'toolset'])

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

/** 工具管理 URL 状态：tab / sdk / mcp，刷新后恢复 */
export function useToolsRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readTab(): ToolsTab {
    const tab = queryString(route.query.tab)
    if (tab && VALID_TABS.has(tab)) return tab as ToolsTab
    return 'sdk'
  }

  function readSdkId(): string | null {
    return queryString(route.query.sdk)
  }

  function readMcpId(): string | null {
    return queryString(route.query.mcp)
  }

  function syncQuery(patch: Partial<{ tab: ToolsTab; sdk: string | null; mcp: string | null }>) {
    const next: Record<string, string> = {}
    for (const [key, value] of Object.entries(route.query)) {
      if (typeof value === 'string') next[key] = value
    }
    if (patch.tab !== undefined) {
      if (patch.tab === 'sdk') delete next.tab
      else next.tab = patch.tab
    }
    if (patch.sdk !== undefined) {
      if (!patch.sdk) delete next.sdk
      else next.sdk = patch.sdk
    }
    if (patch.mcp !== undefined) {
      if (!patch.mcp) delete next.mcp
      else next.mcp = patch.mcp
    }
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readTab, readSdkId, readMcpId, syncQuery }
}
