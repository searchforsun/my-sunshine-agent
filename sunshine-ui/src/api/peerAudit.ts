import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

/** MsgHub 单条发言审计 */
export interface PeerTranscriptEntry {
  round?: number
  roleName?: string
  skillId?: string
  expertId?: string
  displayName?: string
  speakSeq?: number
  content: string
}

export interface PeerRunAuditView {
  messageId: string
  templateId: string
  transcriptJson: string
  updatedAt?: string
}

export function parsePeerTranscript(json: string | undefined | null): PeerTranscriptEntry[] {
  if (!json?.trim()) return []
  try {
    const parsed = JSON.parse(json) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.filter((row): row is PeerTranscriptEntry => {
      if (typeof row !== 'object' || row == null) return false
      const entry = row as PeerTranscriptEntry
      return typeof entry.content === 'string'
        && (typeof entry.roleName === 'string' || typeof entry.displayName === 'string')
    })
  } catch {
    return []
  }
}

export async function fetchPeerRun(messageId: string): Promise<PeerRunAuditView | null> {
  const res = await fetch(
    `${resolveApiBase()}/api/audit/peer-run/${encodeURIComponent(messageId)}`,
    { headers: apiHeaders() },
  )
  if (res.status === 404) return null
  return parseApiResponse<PeerRunAuditView>(res)
}
