import { useRoute, useRouter } from 'vue-router'

export type KbWorkbenchTab = 'docs' | 'debug' | 'config' | 'eval'

const VALID_TABS = new Set<string>(['docs', 'debug', 'config', 'eval'])

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

/** 知识库工作台 URL 状态：tab / kb / doc，刷新后恢复 */
export function useKbWorkbenchRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readTab(): KbWorkbenchTab {
    const tab = queryString(route.query.tab)
    if (tab && VALID_TABS.has(tab)) return tab as KbWorkbenchTab
    return 'docs'
  }

  function readKbId(): string | null {
    return queryString(route.query.kb)
  }

  function readDocId(): string | null {
    return queryString(route.query.doc)
  }

  function syncQuery(patch: Partial<{ tab: KbWorkbenchTab; kb: string | null; doc: string | null }>) {
    const next: Record<string, string> = {}
    for (const [key, value] of Object.entries(route.query)) {
      if (typeof value === 'string') next[key] = value
    }
    if (patch.tab !== undefined) {
      if (patch.tab === 'docs') delete next.tab
      else next.tab = patch.tab
    }
    if (patch.kb !== undefined) {
      if (!patch.kb) delete next.kb
      else next.kb = patch.kb
    }
    if (patch.doc !== undefined) {
      if (!patch.doc) delete next.doc
      else next.doc = patch.doc
    }
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readTab, readKbId, readDocId, syncQuery }
}
