import { apiHeaders } from '../stores/authStore'
import { BFF_STREAM_BASE } from './config'
import { parseBffPayload } from './apiError'

const API_BASE = BFF_STREAM_BASE

export async function confirmToolExecution(token: string, approved: boolean): Promise<boolean> {
  const response = await fetch(`${API_BASE}/api/chat/confirm-tool`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ token, approved }),
  })
  const body = await parseBffPayload<{ accepted?: boolean }>(response)
  return body.accepted === true
}
