import type { AgentCatalogIndexEntry } from '../api/agents'
import type { ExecutionPreference } from '../api/executionModes'
import { allowsAgentMention } from '../api/executionModes'

const DOLLAR_TOKEN = /\$([\w\u4e00-\u9fff-]+)/g

const TOKEN_BOUNDARY = /[\s，。！？,.!?;；：:]/

export type AgentMentionSegment =
  | { type: 'text'; value: string }
  | { type: 'agent'; token: string; agent: AgentCatalogIndexEntry }

export function findAgentByToken(
  token: string,
  catalog: AgentCatalogIndexEntry[],
): AgentCatalogIndexEntry | undefined {
  const lower = token.toLowerCase()
  return catalog.find(e => e.enabled && (
    e.id.toLowerCase() === lower
    || e.displayName.toLowerCase() === lower
  ))
}

export function segmentAgentMentions(
  content: string,
  catalog: AgentCatalogIndexEntry[],
): AgentMentionSegment[] {
  if (!content) return [{ type: 'text', value: '' }]
  const segments: AgentMentionSegment[] = []
  let lastIndex = 0
  DOLLAR_TOKEN.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = DOLLAR_TOKEN.exec(content)) !== null) {
    const token = m[1]
    const agent = findAgentByToken(token, catalog)
    if (!agent) continue
    const afterIdx = m.index + m[0].length
    const afterChar = content[afterIdx]
    if (afterChar != null && !TOKEN_BOUNDARY.test(afterChar)) continue
    if (m.index > lastIndex) {
      segments.push({ type: 'text', value: content.slice(lastIndex, m.index) })
    }
    segments.push({ type: 'agent', token: agent.id, agent })
    lastIndex = afterIdx
  }
  if (lastIndex < content.length) {
    segments.push({ type: 'text', value: content.slice(lastIndex) })
  }
  return segments.length > 0 ? segments : [{ type: 'text', value: content }]
}

export function segmentAgentMentionsForMessage(
  content: string,
  catalog: AgentCatalogIndexEntry[],
  executionPreference?: ExecutionPreference,
): AgentMentionSegment[] {
  const pref = executionPreference ?? 'fast'
  if (!allowsAgentMention(pref)) {
    return [{ type: 'text', value: content }]
  }
  return segmentAgentMentions(content, catalog)
}
