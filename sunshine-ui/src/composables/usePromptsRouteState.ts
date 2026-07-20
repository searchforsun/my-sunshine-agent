import { useRoute, useRouter } from 'vue-router'

export type PromptsTab = 'system' | 'routing' | 'react'
export type PromptsRoutingPane = 'editor' | 'dry-run'
export type PromptsSystemPane = 'editor' | 'principles'

const VALID_TABS = new Set<string>(['system', 'routing', 'react', 'all'])

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

/** 提示词页 URL：tab / id / pane / view；默认第一个 Tab「系统配置」不写 tab */
export function usePromptsRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readTab(): PromptsTab {
    const tab = queryString(route.query.tab)
    if (!tab || !VALID_TABS.has(tab)) return 'system'
    if (tab === 'all') return 'system'
    return tab as PromptsTab
  }

  function readId(): string | null {
    return queryString(route.query.id)
  }

  function readPane(): PromptsRoutingPane {
    return queryString(route.query.pane) === 'dry-run' ? 'dry-run' : 'editor'
  }

  function readSystemPane(): PromptsSystemPane {
    return queryString(route.query.view) === 'principles' ? 'principles' : 'editor'
  }

  function hasExplicitTab(): boolean {
    const tab = queryString(route.query.tab)
    return tab != null && VALID_TABS.has(tab)
  }

  function syncQuery(
    patch: Partial<{
      tab: PromptsTab
      id: string | null
      pane: PromptsRoutingPane
      systemPane: PromptsSystemPane
    }>,
  ) {
    const next: Record<string, string> = {}
    for (const [key, value] of Object.entries(route.query)) {
      if (typeof value === 'string') next[key] = value
    }
    if (patch.tab !== undefined) {
      if (patch.tab === 'system') delete next.tab
      else next.tab = patch.tab
    }
    if (patch.id !== undefined) {
      if (!patch.id) delete next.id
      else next.id = patch.id
    }
    if (patch.pane !== undefined) {
      if (patch.pane === 'editor') delete next.pane
      else next.pane = patch.pane
    }
    if (patch.systemPane !== undefined) {
      if (patch.systemPane === 'editor') delete next.view
      else next.view = 'principles'
    }
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readTab, readId, readPane, readSystemPane, hasExplicitTab, syncQuery }
}
