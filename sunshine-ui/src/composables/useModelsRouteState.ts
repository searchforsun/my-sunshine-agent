import { useRoute, useRouter } from 'vue-router'

export type ModelsTab = 'providers' | 'models' | 'scenes' | 'routes'

const VALID_TABS = new Set<string>(['providers', 'models', 'scenes', 'routes'])

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

/** 模型注册 URL：tab=providers|models|scenes；默认「供应商」不写 tab，刷新可恢复 */
export function useModelsRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readTab(): ModelsTab {
    const tab = queryString(route.query.tab)
    if (tab && VALID_TABS.has(tab)) return tab as ModelsTab
    return 'providers'
  }

  function syncQuery(patch: Partial<{ tab: ModelsTab }>) {
    const next: Record<string, string> = {}
    for (const [key, value] of Object.entries(route.query)) {
      if (typeof value === 'string') next[key] = value
    }
    if (patch.tab !== undefined) {
      // 默认 tab 不占 query，刷新 /models 仍落在供应商
      if (patch.tab === 'providers') delete next.tab
      else next.tab = patch.tab
    }
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readTab, syncQuery }
}
