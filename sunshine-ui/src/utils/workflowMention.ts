import type { WorkflowCatalogEntry } from '../api/workflows'
import type { ExecutionPreference } from '../api/executionModes'
import { allowsWorkflowMention } from '../api/executionModes'

const HASH_TOKEN = /#([\w\u4e00-\u9fff-]+)/g

const TOKEN_BOUNDARY = /[\s，。！？,.!?;；：:]/

export type WorkflowMentionSegment =
  | { type: 'text'; value: string }
  | { type: 'workflow'; token: string; workflow: WorkflowCatalogEntry }

export function findWorkflowByToken(
  token: string,
  catalog: WorkflowCatalogEntry[],
): WorkflowCatalogEntry | undefined {
  const lower = token.toLowerCase()
  return catalog.find(w =>
    w.id.toLowerCase() === lower
    || w.displayName.toLowerCase() === lower,
  )
}

export function segmentWorkflowMentions(
  content: string,
  catalog: WorkflowCatalogEntry[],
): WorkflowMentionSegment[] {
  if (!content) return [{ type: 'text', value: '' }]
  const segments: WorkflowMentionSegment[] = []
  let lastIndex = 0
  HASH_TOKEN.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = HASH_TOKEN.exec(content)) !== null) {
    const token = m[1]
    const workflow = findWorkflowByToken(token, catalog)
    if (!workflow) continue
    const afterIdx = m.index + m[0].length
    const afterChar = content[afterIdx]
    if (afterChar != null && !TOKEN_BOUNDARY.test(afterChar)) continue
    if (m.index > lastIndex) {
      segments.push({ type: 'text', value: content.slice(lastIndex, m.index) })
    }
    segments.push({ type: 'workflow', token: workflow.id, workflow })
    lastIndex = afterIdx
  }
  if (lastIndex < content.length) {
    segments.push({ type: 'text', value: content.slice(lastIndex) })
  }
  return segments.length > 0 ? segments : [{ type: 'text', value: content }]
}

export function segmentWorkflowMentionsForMessage(
  content: string,
  catalog: WorkflowCatalogEntry[],
  executionPreference?: ExecutionPreference,
): WorkflowMentionSegment[] {
  const pref = executionPreference ?? 'fast'
  if (!allowsWorkflowMention(pref)) {
    return [{ type: 'text', value: content }]
  }
  return segmentWorkflowMentions(content, catalog)
}

export interface WorkflowBindingForSend {
  workflowId?: string
}

/** 发送前解析首个 catalog 内 #workflow，供 chat 请求 workflowId 入参 */
export function resolveWorkflowBindingForSend(
  content: string,
  catalog: WorkflowCatalogEntry[],
  executionPreference?: ExecutionPreference,
): WorkflowBindingForSend {
  const pref = executionPreference ?? 'fast'
  if (!allowsWorkflowMention(pref)) {
    return {}
  }
  const first = segmentWorkflowMentions(content, catalog).find(s => s.type === 'workflow')
  if (!first || first.type !== 'workflow') {
    return {}
  }
  return { workflowId: first.workflow.id }
}
