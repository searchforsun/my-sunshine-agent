import { useRoute, useRouter } from 'vue-router'
import type { BizDomain, BizTable } from '../api/bizData'
import { BIZ_DOMAINS, BIZ_TABLE_DEFS } from '../utils/bizTableSchema'

function queryString(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed || null
}

const DOMAIN_SET = new Set(BIZ_DOMAINS.map(d => d.key))

/** 业务数据 URL：?domain=&table=，刷新后恢复 */
export function useBizDataRouteState() {
  const route = useRoute()
  const router = useRouter()

  function readDomain(): BizDomain {
    const d = queryString(route.query.domain)
    if (d && DOMAIN_SET.has(d as BizDomain)) return d as BizDomain
    return 'finance'
  }

  function readTable(domain: BizDomain): BizTable {
    const t = queryString(route.query.table)
    const defs = BIZ_TABLE_DEFS[domain]
    if (t && defs.some(d => d.key === t)) return t as BizTable
    return defs[0].key
  }

  function syncQuery(state: { domain: BizDomain; table: BizTable }) {
    const next: Record<string, string> = {
      domain: state.domain,
      table: state.table,
    }
    const same =
      Object.keys(next).length === Object.keys(route.query).length
      && Object.entries(next).every(([k, v]) => route.query[k] === v)
    if (same) return
    void router.replace({ query: next })
  }

  return { readDomain, readTable, syncQuery }
}
