import { apiHeaders } from '../stores/authStore'
import { resolveBffStreamBase } from './config'
import type { DecisionAnswerView } from './processingSteps'

const API_BASE = () => resolveBffStreamBase()

export async function resolveDecision(
  generationId: string,
  token: string,
  answers: DecisionAnswerView[],
): Promise<{ accepted?: boolean }> {
  const res = await fetch(
    `${API_BASE()}/api/generations/${encodeURIComponent(generationId)}/decisions/${encodeURIComponent(token)}/resolve`,
    {
      method: 'POST',
      headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ answers }),
    },
  )
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw Object.assign(new Error(err.message || res.statusText), { status: res.status, body: err })
  }
  return res.json()
}
