import { apiHeaders } from '../stores/authStore'
import { resolveBffStreamBase } from './config'
import { parseBffPayload } from './apiError'

export async function confirmToolExecution(token: string, approved: boolean): Promise<boolean> {
  const response = await fetch(`${resolveBffStreamBase()}/api/chat/confirm-tool`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ token, approved }),
  })
  const body = await parseBffPayload<{ accepted?: boolean }>(response)
  return body.accepted === true
}
