import { apiHeaders } from '../stores/authStore'
import { resolveBffStreamBase } from './config'

const API_BASE = () => resolveBffStreamBase()

export async function resolveDecision(
  generationId: string,
  token: string,
  choice: string,
  customInput?: string,
): Promise<{ accepted?: boolean }> {
  const res = await fetch(
    `${API_BASE()}/api/generations/${encodeURIComponent(generationId)}/decisions/${encodeURIComponent(token)}/resolve`,
    {
      method: 'POST',
      headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        choice,
        ...(customInput != null && customInput !== '' ? { customInput } : {}),
      }),
    },
  )
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw Object.assign(new Error(err.message || res.statusText), { status: res.status, body: err })
  }
  return res.json()
}
