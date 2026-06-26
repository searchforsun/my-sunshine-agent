import { apiHeaders } from '../stores/authStore'
import { resolveBffStreamBase } from './config'
import { parseBffPayload } from './apiError'

export async function confirmWorkflowNodeRecovery(
  token: string,
  action: 'retry' | 'terminate' | 'skip',
): Promise<boolean> {
  const response = await fetch(`${resolveBffStreamBase()}/api/chat/workflow-node-recovery`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({ token, action }),
  })
  const body = await parseBffPayload<{ accepted?: boolean }>(response)
  return body.accepted === true
}
