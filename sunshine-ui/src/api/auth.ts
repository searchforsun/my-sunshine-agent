import { apiHeaders } from '../stores/authStore'

export interface AuthUser {
  userId: string
  username: string
  nickname: string
}

export interface LoginResult extends AuthUser {
  token: string
  tokenName: string
}

interface ApiResult<T> {
  code: number
  msg: string
  data: T
}

async function parseResponse<T>(res: Response): Promise<T> {
  const body = (await res.json()) as ApiResult<T>
  if (body.code !== 200) {
    throw new Error(body.msg || `HTTP ${res.status}`)
  }
  return body.data
}

export async function register(username: string, password: string, nickname?: string): Promise<AuthUser> {
  const res = await fetch('/api/auth/register', {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ username, password, nickname }),
  })
  return parseResponse<AuthUser>(res)
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ username, password }),
  })
  return parseResponse<LoginResult>(res)
}

export async function logout(): Promise<void> {
  const res = await fetch('/api/auth/logout', {
    method: 'POST',
    headers: apiHeaders(),
  })
  await parseResponse<null>(res)
}

export async function me(): Promise<AuthUser> {
  const res = await fetch('/api/auth/me', { headers: apiHeaders() })
  if (res.status === 401) {
    throw new Error('未登录')
  }
  return parseResponse<AuthUser>(res)
}

export async function updateProfile(nickname: string): Promise<AuthUser> {
  const res = await fetch('/api/auth/profile', {
    method: 'PATCH',
    headers: apiHeaders(),
    body: JSON.stringify({ nickname }),
  })
  return parseResponse<AuthUser>(res)
}
