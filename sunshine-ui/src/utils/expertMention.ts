import type { ExpertCatalogIndexEntry } from '../api/experts'
import type { ExecutionPreference } from '../api/executionModes'
import { allowsExpertMention } from '../api/executionModes'

const DOLLAR_TOKEN = /\$([\w\u4e00-\u9fff-]+)/g

const TOKEN_BOUNDARY = /[\s，。！？,.!?;；：:]/

export type ExpertMentionSegment =
  | { type: 'text'; value: string }
  | { type: 'expert'; token: string; expert: ExpertCatalogIndexEntry }

export function findExpertByToken(
  token: string,
  catalog: ExpertCatalogIndexEntry[],
): ExpertCatalogIndexEntry | undefined {
  const lower = token.toLowerCase()
  return catalog.find(e => e.enabled && (
    e.id.toLowerCase() === lower
    || e.displayName.toLowerCase() === lower
  ))
}

export function segmentExpertMentions(
  content: string,
  catalog: ExpertCatalogIndexEntry[],
): ExpertMentionSegment[] {
  if (!content) return [{ type: 'text', value: '' }]
  const segments: ExpertMentionSegment[] = []
  let lastIndex = 0
  DOLLAR_TOKEN.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = DOLLAR_TOKEN.exec(content)) !== null) {
    const token = m[1]
    const expert = findExpertByToken(token, catalog)
    if (!expert) continue
    const afterIdx = m.index + m[0].length
    const afterChar = content[afterIdx]
    if (afterChar != null && !TOKEN_BOUNDARY.test(afterChar)) continue
    if (m.index > lastIndex) {
      segments.push({ type: 'text', value: content.slice(lastIndex, m.index) })
    }
    segments.push({ type: 'expert', token: expert.id, expert })
    lastIndex = afterIdx
  }
  if (lastIndex < content.length) {
    segments.push({ type: 'text', value: content.slice(lastIndex) })
  }
  return segments.length > 0 ? segments : [{ type: 'text', value: content }]
}

export function segmentExpertMentionsForMessage(
  content: string,
  catalog: ExpertCatalogIndexEntry[],
  executionPreference?: ExecutionPreference,
): ExpertMentionSegment[] {
  const pref = executionPreference ?? 'auto'
  if (!allowsExpertMention(pref)) {
    return [{ type: 'text', value: content }]
  }
  return segmentExpertMentions(content, catalog)
}
