import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

export interface AuthUser {
  userId: string
  username: string
  nickname: string
  tenantId: string
  /** never|always|smart */
  defaultWriteHitlMode?: string
}

export interface LoginResult extends AuthUser {
  token: string
  tokenName: string
}

export interface UpdateProfileResult extends AuthUser {
  token: string
}

export async function register(username: string, password: string, nickname?: string): Promise<AuthUser> {
  const res = await fetch(`${resolveApiBase()}/api/auth/register`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ username, password, nickname }),
  })
  return parseApiResponse<AuthUser>(res)
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const res = await fetch(`${resolveApiBase()}/api/auth/login`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ username, password }),
  })
  return parseApiResponse<LoginResult>(res)
}

export async function logout(): Promise<void> {
  const res = await fetch(`${resolveApiBase()}/api/auth/logout`, {
    method: 'POST',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function me(): Promise<AuthUser> {
  const res = await fetch(`${resolveApiBase()}/api/auth/me`, { headers: apiHeaders() })
  return parseApiResponse<AuthUser>(res)
}

export async function updateProfile(
  nickname: string,
  tenantId: string,
  defaultWriteHitlMode?: string,
): Promise<UpdateProfileResult> {
  const res = await fetch(`${resolveApiBase()}/api/auth/profile`, {
    method: 'PATCH',
    headers: apiHeaders(),
    body: JSON.stringify({ nickname, tenantId, defaultWriteHitlMode }),
  })
  return parseApiResponse<UpdateProfileResult>(res)
}

/** 租户下启用用户列表（业务数据页 userId 下拉）；字段为 userId，非 id。 */
export async function listAuthUsers(
  tenantId = 'default',
): Promise<Array<{ userId: string; username: string; nickname: string }>> {
  const q = new URLSearchParams({ tenantId })
  const res = await fetch(`${resolveApiBase()}/api/auth/users?${q}`, {
    headers: apiHeaders(),
  })
  return parseApiResponse<Array<{ userId: string; username: string; nickname: string }>>(res)
}
