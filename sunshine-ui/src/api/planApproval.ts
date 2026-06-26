import { apiHeaders } from '../stores/authStore'
import { resolveBffStreamBase } from './config'
import { parseBffPayload } from './apiError'

export type PlanApprovalAction = 'approve' | 'regenerate'

export async function confirmPlanExecution(
  token: string,
  action: PlanApprovalAction,
  modificationHint?: string,
): Promise<boolean> {
  const response = await fetch(`${resolveBffStreamBase()}/api/chat/confirm-plan`, {
    method: 'POST',
    headers: apiHeaders(),
    body: JSON.stringify({
      token,
      action,
      modificationHint: modificationHint?.trim() || undefined,
    }),
  })
  const body = await parseBffPayload<{ accepted?: boolean }>(response)
  return body.accepted === true
}
