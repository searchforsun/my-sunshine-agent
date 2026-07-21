import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

const ADMIN_TOKEN =
  import.meta.env.VITE_BIZ_ADMIN_TOKEN
  ?? 'sunshine-biz-admin-dev'

export type BizDomain = 'finance' | 'hr' | 'oa'

export type BizTable =
  | 'expenses'
  | 'inbox'
  | 'tasks'
  | 'leave-balances'
  | 'leave-requests'
  | 'attendance-months'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

function bizHeaders(): Record<string, string> {
  return {
    ...apiHeaders(),
    'X-Admin-Token': ADMIN_TOKEN,
  }
}

function tableBase(domain: BizDomain, table: string): string {
  return `/api/biz/${domain}/${table}`
}

/** 更新/删除路径：HR 复合键表用 userId+year / userId+yearMonth，其余用 id。 */
export function bizRowPath(
  domain: BizDomain,
  table: string,
  row: Record<string, unknown>,
): string {
  const base = tableBase(domain, table)
  if (domain === 'hr' && table === 'leave-balances') {
    return `${base}/${encodeURIComponent(String(row.userId))}/${encodeURIComponent(String(row.year))}`
  }
  if (domain === 'hr' && table === 'attendance-months') {
    return `${base}/${encodeURIComponent(String(row.userId))}/${encodeURIComponent(String(row.yearMonth))}`
  }
  return `${base}/${encodeURIComponent(String(row.id))}`
}

export async function listBizRows(
  domain: BizDomain,
  table: string,
  query: Record<string, string> = {},
): Promise<Record<string, unknown>[]> {
  const q = new URLSearchParams(query)
  const res = await fetch(`${apiUrl(tableBase(domain, table))}?${q}`, {
    headers: bizHeaders(),
  })
  return parseApiResponse<Record<string, unknown>[]>(res)
}

export async function createBizRow(
  domain: BizDomain,
  table: string,
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const res = await fetch(apiUrl(tableBase(domain, table)), {
    method: 'POST',
    headers: bizHeaders(),
    body: JSON.stringify(body),
  })
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function updateBizRow(
  domain: BizDomain,
  table: string,
  rowKey: Record<string, unknown>,
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const res = await fetch(apiUrl(bizRowPath(domain, table, rowKey)), {
    method: 'PUT',
    headers: bizHeaders(),
    body: JSON.stringify(body),
  })
  return parseApiResponse<Record<string, unknown>>(res)
}

export async function deleteBizRow(
  domain: BizDomain,
  table: string,
  rowKey: Record<string, unknown>,
  query: Record<string, string> = {},
): Promise<void> {
  const q = new URLSearchParams(query)
  const suffix = q.toString() ? `?${q}` : ''
  const res = await fetch(`${apiUrl(bizRowPath(domain, table, rowKey))}${suffix}`, {
    method: 'DELETE',
    headers: bizHeaders(),
  })
  await parseApiResponse<Record<string, unknown>>(res, { allowEmptyData: true })
}
