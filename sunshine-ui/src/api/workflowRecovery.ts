import { apiHeaders } from '../stores/authStore'
import { BFF_STREAM_BASE } from './config'
import { parseBffPayload } from './apiError'

const API_BASE = BFF_STREAM_BASE

export async function confirmWorkflowNodeRecovery(
  token: string,
  action: 'retry' | 'terminate' | 'skip',
): Promise<boolean> {
  const response = await fetch(`${API_BASE}/api/chat/workflow-node-recovery`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ token, action }),
  })
  const body = await parseBffPayload<{ accepted?: boolean }>(response)
  return body.accepted === true
}
