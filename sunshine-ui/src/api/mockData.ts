import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

const ADMIN_TOKEN = import.meta.env.VITE_MOCK_ADMIN_TOKEN ?? 'sunshine-mock-admin-dev'

export type MockDomain = 'finance' | 'oa' | 'hr'

export interface FinanceSnapshot {
  userId: string
  tenantId: string
  expenses: Array<Record<string, unknown>>
  inbox: Array<Record<string, unknown>>
}

export interface OaSnapshot {
  userId: string
  tenantId: string
  tasks: Array<Record<string, unknown>>
}

export interface HrSnapshot {
  userId: string
  tenantId: string
  leaveBalance: Record<string, unknown> | null
  leaveRequests: Array<Record<string, unknown>>
  attendance: Record<string, unknown>
}

export type MockSnapshot = FinanceSnapshot | OaSnapshot | HrSnapshot

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

function mockHeaders(): Record<string, string> {
  return {
    ...apiHeaders(),
    'X-Admin-Token': ADMIN_TOKEN,
  }
}

function domainBase(domain: MockDomain): string {
  return `/api/mock/${domain}`
}

export async function listMockUsers(
  domain: MockDomain,
  tenantId = 'default',
): Promise<string[]> {
  const q = new URLSearchParams({ tenantId })
  const res = await fetch(`${apiUrl(domainBase(domain))}/users?${q}`, {
    headers: mockHeaders(),
  })
  return parseApiResponse<string[]>(res)
}

export async function fetchMockSnapshot(
  domain: MockDomain,
  userId: string,
  tenantId = 'default',
): Promise<MockSnapshot> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(`${apiUrl(domainBase(domain))}/snapshot?${q}`, {
    headers: mockHeaders(),
  })
  return parseApiResponse<MockSnapshot>(res)
}

export async function resetMockData(
  domain: MockDomain,
  tenantId = 'default',
): Promise<{ tenantId: string; status: string }> {
  const q = new URLSearchParams({ tenantId })
  const res = await fetch(`${apiUrl(domainBase(domain))}/reset?${q}`, {
    method: 'POST',
    headers: mockHeaders(),
  })
  return parseApiResponse<{ tenantId: string; status: string }>(res)
}

export async function patchExpenseStatus(
  expenseId: string,
  userId: string,
  status: string,
  tenantId = 'default',
): Promise<Record<string, unknown>> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(
    `${apiUrl(domainBase('finance'))}/expenses/${encodeURIComponent(expenseId)}?${q}`,
    {
      method: 'PATCH',
      headers: {
        ...mockHeaders(),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ status }),
    },
  )
  return parseApiResponse<Record<string, unknown>>(res)
}
