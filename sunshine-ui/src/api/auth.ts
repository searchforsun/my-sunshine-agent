import { apiHeaders } from '../stores/authStore'
import { parseApiResponse } from './apiError'

export interface AuthUser {
  userId: string
  username: string
  nickname: string
  tenantId: string
}

export interface LoginResult extends AuthUser {
  token: string
  tokenName: string
}

export interface UpdateProfileResult extends AuthUser {
  token: string
}

export async function register(username: string, password: string, nickname?: string): Promise<AuthUser> {
  const res = await fetch('/api/auth/register', {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ username, password, nickname }),
  })
  return parseApiResponse<AuthUser>(res)
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ username, password }),
  })
  return parseApiResponse<LoginResult>(res)
}

export async function logout(): Promise<void> {
  const res = await fetch('/api/auth/logout', {
    method: 'POST',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function me(): Promise<AuthUser> {
  const res = await fetch('/api/auth/me', { headers: apiHeaders() })
  return parseApiResponse<AuthUser>(res)
}

export async function updateProfile(nickname: string, tenantId: string): Promise<UpdateProfileResult> {
  const res = await fetch('/api/auth/profile', {
    method: 'PATCH',
    headers: apiHeaders(),
    body: JSON.stringify({ nickname, tenantId }),
  })
  return parseApiResponse<UpdateProfileResult>(res)
}
