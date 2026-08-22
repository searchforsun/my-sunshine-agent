import type { SkillCatalogIndexEntry } from '../api/skills'
import type { ExecutionMode } from '../api/executionModes'
import { allowsSkillMention } from '../api/executionModes'

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

export interface SkillBindingForSend {
  skillId?: string
}

/** 发送前解析首个 catalog 内 /skill，供 chat 请求 skillId 入参 */
export function resolveSkillBindingForSend(
  content: string,
  catalog: SkillCatalogIndexEntry[],
  executionPreference?: ExecutionMode,
): SkillBindingForSend {
  const pref = executionPreference ?? 'fast'
  if (!allowsSkillMention(pref)) {
    return {}
  }
  const firstSkill = findSkillByToken(
    content.match(/\/([\w\u4e00-\u9fff-]+)/)?.[1] ?? '',
    catalog,
  )
  return firstSkill ? { skillId: firstSkill.id } : {}
}
