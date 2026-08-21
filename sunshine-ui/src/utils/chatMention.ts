import type { SkillCatalogIndexEntry } from '../api/skills'
import type { AgentCatalogIndexEntry } from '../api/agents'
import type { WorkflowCatalogEntry } from '../api/workflows'
import type { ExecutionMode } from '../api/executionModes'
import {
  allowsAgentMention,
  allowsSkillMention,
  allowsWorkflowMention,
} from '../api/executionModes'
import { findSkillByToken } from './skillMention'
import { findAgentByToken } from './agentMention'
import { findWorkflowByToken } from './workflowMention'
import {
  collectSandboxPathMatches,
  sandboxPathBasename,
  sandboxPathPlainToken,
} from './sandboxPathChip'

const TOKEN_BOUNDARY = /[\s，。！？,.!?;；：:]/

export type ChatMentionKind = 'skill' | 'agent' | 'workflow' | 'path'

export type ChatMentionSegment =
  | { type: 'text'; value: string }
  | { type: 'skill'; token: string; skill: SkillCatalogIndexEntry }
  | { type: 'agent'; token: string; agent: AgentCatalogIndexEntry }
  | { type: 'workflow'; token: string; workflow: WorkflowCatalogEntry }
  | { type: 'path'; token: string; label: string; lineStart?: number; lineEnd?: number }

export interface ChatMentionCatalogs {
  skills: SkillCatalogIndexEntry[]
  agents: AgentCatalogIndexEntry[]
  workflows: WorkflowCatalogEntry[]
}

export interface ChatMentionAllows {
  skill: boolean
  agent: boolean
  workflow: boolean
}

interface RawMentionMatch {
  index: number
  end: number
  kind: ChatMentionKind
  token: string
  label?: string
  lineStart?: number
  lineEnd?: number
}

const PREFIX_RE: { kind: Exclude<ChatMentionKind, 'path'>; re: RegExp }[] = [
  { kind: 'skill', re: /\/([\w\u4e00-\u9fff-]+)/g },
  { kind: 'agent', re: /\$([\w\u4e00-\u9fff-]+)/g },
  { kind: 'workflow', re: /#([\w\u4e00-\u9fff-]+)/g },
]

function resolveMention(
  kind: Exclude<ChatMentionKind, 'path'>,
  token: string,
  catalogs: ChatMentionCatalogs,
): ChatMentionSegment | null {
  if (kind === 'skill') {
    const skill = findSkillByToken(token, catalogs.skills)
    return skill ? { type: 'skill', token: skill.id, skill } : null
  }
  if (kind === 'agent') {
    const agent = findAgentByToken(token, catalogs.agents)
    return agent ? { type: 'agent', token: agent.id, agent } : null
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
  for (const hit of collectSandboxPathMatches(content)) {
    matches.push({
      index: hit.index,
      end: hit.end,
      kind: 'path',
      token: hit.path,
      label: sandboxPathBasename(hit.path),
      lineStart: hit.lineStart,
      lineEnd: hit.lineEnd,
    })
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
    if (hit.kind === 'path') {
      if (hit.index > lastIndex) {
        segments.push({ type: 'text', value: content.slice(lastIndex, hit.index) })
      }
      segments.push({
        type: 'path',
        token: hit.token,
        label: hit.label || sandboxPathBasename(hit.token),
        lineStart: hit.lineStart,
        lineEnd: hit.lineEnd,
      })
      lastIndex = hit.end
      continue
    }
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

export function allowsForPreference(preference: ExecutionMode): ChatMentionAllows {
  return {
    skill: allowsSkillMention(preference),
    agent: allowsAgentMention(preference),
    workflow: allowsWorkflowMention(preference),
  }
}

export function segmentChatMentionsForMessage(
  content: string,
  catalogs: ChatMentionCatalogs,
  executionPreference?: ExecutionMode,
): ChatMentionSegment[] {
  const pref = executionPreference ?? 'fast'
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
    case 'skill': return '/'
    case 'agent': return '$'
    case 'workflow': return '#'
    case 'path': return ''
  }
}

export function mentionPlainToken(
  kind: ChatMentionKind,
  token: string,
  lineStart?: number,
  lineEnd?: number,
): string {
  if (kind === 'path') return sandboxPathPlainToken(token, lineStart, lineEnd)
  return `${mentionPrefix(kind)}${token}`
}
