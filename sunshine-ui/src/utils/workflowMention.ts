import type { WorkflowCatalogEntry } from '../api/workflows'
import type { ExecutionMode } from '../api/executionModes'
import { allowsWorkflowMention } from '../api/executionModes'

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

export interface WorkflowBindingForSend {
  workflowId?: string
}

/** 发送前解析首个 catalog 内 #workflow，供 chat 请求 workflowId 入参 */
export function resolveWorkflowBindingForSend(
  content: string,
  catalog: WorkflowCatalogEntry[],
  executionPreference?: ExecutionMode,
): WorkflowBindingForSend {
  const pref = executionPreference ?? 'fast'
  if (!allowsWorkflowMention(pref)) {
    return {}
  }
  const first = findWorkflowByToken(
    content.match(/#([\w\u4e00-\u9fff-]+)/)?.[1] ?? '',
    catalog,
  )
  return first ? { workflowId: first.id } : {}
}
