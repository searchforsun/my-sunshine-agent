import type { SkillCatalogIndexEntry } from '../api/skills'
import type { ExecutionPreference } from '../api/executionModes'
import { allowsSkillMention } from '../api/executionModes'

const AT_TOKEN = /@([\w\u4e00-\u9fff-]+)/g

/** token 后须为空白、标点或串尾，避免误匹配更长单词 */
const TOKEN_BOUNDARY = /[\s，。！？,.!?;；：:]/

export type SkillMentionSegment =
  | { type: 'text'; value: string }
  | { type: 'skill'; token: string; skill: SkillCatalogIndexEntry }

export function findSkillByToken(
  token: string,
  catalog: SkillCatalogIndexEntry[],
): SkillCatalogIndexEntry | undefined {
  const lower = token.toLowerCase()
  return catalog.find(s => s.enabled && (
    s.id.toLowerCase() === lower
    || s.displayName.toLowerCase() === lower
  ))
}

/** 将正文拆成文本段 + catalog 内 skill chip（位置不限） */
export function segmentSkillMentions(
  content: string,
  catalog: SkillCatalogIndexEntry[],
): SkillMentionSegment[] {
  if (!content) return [{ type: 'text', value: '' }]
  const segments: SkillMentionSegment[] = []
  let lastIndex = 0
  AT_TOKEN.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = AT_TOKEN.exec(content)) !== null) {
    const token = m[1]
    const skill = findSkillByToken(token, catalog)
    if (!skill) continue
    const afterIdx = m.index + m[0].length
    const afterChar = content[afterIdx]
    if (afterChar != null && !TOKEN_BOUNDARY.test(afterChar)) continue
    if (m.index > lastIndex) {
      segments.push({ type: 'text', value: content.slice(lastIndex, m.index) })
    }
    segments.push({ type: 'skill', token: skill.id, skill })
    lastIndex = afterIdx
  }
  if (lastIndex < content.length) {
    segments.push({ type: 'text', value: content.slice(lastIndex) })
  }
  return segments.length > 0 ? segments : [{ type: 'text', value: content }]
}

/** 按消息发送时的 executionPreference 决定是否渲染 chip */
export function segmentSkillMentionsForMessage(
  content: string,
  catalog: SkillCatalogIndexEntry[],
  executionPreference?: ExecutionPreference,
): SkillMentionSegment[] {
  const pref = executionPreference ?? 'auto'
  if (!allowsSkillMention(pref)) {
    return [{ type: 'text', value: content }]
  }
  return segmentSkillMentions(content, catalog)
}

export function hasSkillMentionSegments(
  content: string,
  catalog: SkillCatalogIndexEntry[],
): boolean {
  return segmentSkillMentions(content, catalog).some(s => s.type === 'skill')
}

export interface SkillBindingForSend {
  skillId?: string
}

/** 发送前解析首个 catalog 内 @skill，供 chat 请求 skillId 入参 */
export function resolveSkillBindingForSend(
  content: string,
  catalog: SkillCatalogIndexEntry[],
  executionPreference?: ExecutionPreference,
): SkillBindingForSend {
  const pref = executionPreference ?? 'auto'
  if (!allowsSkillMention(pref)) {
    return {}
  }
  const firstSkill = segmentSkillMentions(content, catalog).find(s => s.type === 'skill')
  if (!firstSkill || firstSkill.type !== 'skill') {
    return {}
  }
  return { skillId: firstSkill.skill.id }
}
