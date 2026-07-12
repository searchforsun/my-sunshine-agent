import type { SkillCatalogIndexEntry } from '../api/skills'
import type { ExpertCatalogIndexEntry } from '../api/experts'
import type { WorkflowCatalogEntry } from '../api/workflows'
import type { ExecutionPreference } from '../api/executionModes'
import {
  allowsExpertMention,
  allowsSkillMention,
  allowsWorkflowMention,
} from '../api/executionModes'
import { findSkillByToken } from './skillMention'
import { findExpertByToken } from './expertMention'
import { findWorkflowByToken } from './workflowMention'

const TOKEN_BOUNDARY = /[\s，。！？,.!?;；：:]/

export type ChatMentionKind = 'skill' | 'expert' | 'workflow'

export type ChatMentionSegment =
  | { type: 'text'; value: string }
  | { type: 'skill'; token: string; skill: SkillCatalogIndexEntry }
  | { type: 'expert'; token: string; expert: ExpertCatalogIndexEntry }
  | { type: 'workflow'; token: string; workflow: WorkflowCatalogEntry }

export interface ChatMentionCatalogs {
  skills: SkillCatalogIndexEntry[]
  experts: ExpertCatalogIndexEntry[]
  workflows: WorkflowCatalogEntry[]
}

export interface ChatMentionAllows {
  skill: boolean
  expert: boolean
  workflow: boolean
}

interface RawMentionMatch {
  index: number
  end: number
  kind: ChatMentionKind
  token: string
}

const PREFIX_RE: { kind: ChatMentionKind; re: RegExp }[] = [
  { kind: 'skill', re: /@([\w\u4e00-\u9fff-]+)/g },
  { kind: 'expert', re: /\$([\w\u4e00-\u9fff-]+)/g },
  { kind: 'workflow', re: /#([\w\u4e00-\u9fff-]+)/g },
]

function resolveMention(
  kind: ChatMentionKind,
  token: string,
  catalogs: ChatMentionCatalogs,
): ChatMentionSegment | null {
  if (kind === 'skill') {
    const skill = findSkillByToken(token, catalogs.skills)
    return skill ? { type: 'skill', token: skill.id, skill } : null
  }
  if (kind === 'expert') {
    const expert = findExpertByToken(token, catalogs.experts)
    return expert ? { type: 'expert', token: expert.id, expert } : null
  }
  const workflow = findWorkflowByToken(token, catalogs.workflows)
  return workflow ? { type: 'workflow', token: workflow.id, workflow } : null
}

function collectMatches(content: string, allows: ChatMentionAllows): RawMentionMatch[] {
  const matches: RawMentionMatch[] = []
  for (const { kind, re } of PREFIX_RE) {
    if (!allows[kind]) continue
    re.lastIndex = 0
    let m: RegExpExecArray | null
    while ((m = re.exec(content)) !== null) {
      const afterIdx = m.index + m[0].length
      const afterChar = content[afterIdx]
      if (afterChar != null && !TOKEN_BOUNDARY.test(afterChar)) continue
      matches.push({ index: m.index, end: afterIdx, kind, token: m[1] })
    }
  }
  return matches.sort((a, b) => a.index - b.index)
}

export function segmentChatMentions(
  content: string,
  catalogs: ChatMentionCatalogs,
  allows: ChatMentionAllows,
): ChatMentionSegment[] {
  if (!content) return [{ type: 'text', value: '' }]
  const raw = collectMatches(content, allows)
  const segments: ChatMentionSegment[] = []
  let lastIndex = 0
  for (const hit of raw) {
    if (hit.index < lastIndex) continue
    const resolved = resolveMention(hit.kind, hit.token, catalogs)
    if (!resolved) continue
    if (hit.index > lastIndex) {
      segments.push({ type: 'text', value: content.slice(lastIndex, hit.index) })
    }
    segments.push(resolved)
    lastIndex = hit.end
  }
  if (lastIndex < content.length) {
    segments.push({ type: 'text', value: content.slice(lastIndex) })
  }
  return segments.length > 0 ? segments : [{ type: 'text', value: content }]
}

export function allowsForPreference(preference: ExecutionPreference): ChatMentionAllows {
  return {
    skill: allowsSkillMention(preference),
    expert: allowsExpertMention(preference),
    workflow: allowsWorkflowMention(preference),
  }
}

export function segmentChatMentionsForMessage(
  content: string,
  catalogs: ChatMentionCatalogs,
  executionPreference?: ExecutionPreference,
): ChatMentionSegment[] {
  const pref = executionPreference ?? 'auto'
  return segmentChatMentions(content, catalogs, allowsForPreference(pref))
}

export function hasChatMentionChips(
  content: string,
  catalogs: ChatMentionCatalogs,
  allows: ChatMentionAllows,
): boolean {
  return segmentChatMentions(content, catalogs, allows).some(s => s.type !== 'text')
}

export function mentionPrefix(kind: ChatMentionKind): string {
  switch (kind) {
    case 'skill': return '@'
    case 'expert': return '$'
    case 'workflow': return '#'
  }
}

export function mentionPlainToken(kind: ChatMentionKind, token: string): string {
  return `${mentionPrefix(kind)}${token}`
}
