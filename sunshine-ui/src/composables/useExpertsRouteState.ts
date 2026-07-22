import { useRoute, useRouter } from 'vue-router'

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

/** 专家页 URL：?id=，刷新后恢复选中项 */
export function useExpertsRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readId(): string | null {
    return queryString(route.query.id)
  }

  function syncId(id: string | null) {
    const next: Record<string, string> = {}
    for (const [key, value] of Object.entries(route.query)) {
      if (typeof value === 'string' && key !== 'id') next[key] = value
    }
    if (id) next.id = id
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readId, syncId }
}
