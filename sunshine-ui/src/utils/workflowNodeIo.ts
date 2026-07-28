import type { ToolCatalogEntry } from '../api/tools'
import type { WorkflowPlanNode } from '../api/workflows'

export interface NodeOutputRef {
  ref: string
  label: string
}

export interface ToolParamField {
  name: string
  description?: string
  required: boolean
}

export function parseToolSchemaFields(tool?: ToolCatalogEntry | null): ToolParamField[] {
  if (!tool?.parameters) return []
  const props = (tool.parameters as { properties?: Record<string, { description?: string }> }).properties
  if (!props || typeof props !== 'object') return []
  const required = new Set(
    Array.isArray((tool.parameters as { required?: string[] }).required)
      ? (tool.parameters as { required?: string[] }).required
      : [],
  )
  return Object.entries(props).map(([name, meta]) => ({
    name,
    description: meta?.description,
    required: required.has(name),
  }))
}

/**
 * 节点下游可引用的输出变量列表。
 * WF-1 结构化 I/O 后 tool 节点输出统一为 `{{id.output}}`（完整对象，可嵌套取值）；
 * summary 仅在工具 Catalog 配置了摘要模板时展示。
 */
export function nodeOutputRefs(node: WorkflowPlanNode, tool?: ToolCatalogEntry | null): NodeOutputRef[] {
  const id = node.id
  switch (node.type) {
    case 'tool': {
      const refs: NodeOutputRef[] = [{ ref: `{{${id}.output}}`, label: '完整输出' }]
      const hasSummaryTemplate = !!tool?.timelineSummaryTemplate?.trim()
      if (hasSummaryTemplate) {
        refs.push({ ref: `{{${id}.summary}}`, label: '时间线摘要' })
      }
      return refs
    }
    case 'rag':
      return [{ ref: `{{${id}.output}}`, label: '检索结果正文' }]
    case 'agent':
      return [
        { ref: `{{${id}.answer}}`, label: '分析结论（推荐下游引用）' },
        { ref: `{{${id}.output}}`, label: '同 answer' },
      ]
    default:
      return []
  }
}

export function readToolParamValue(params: Record<string, unknown> | undefined, name: string): string {
  const raw = params?.[name]
  return raw != null ? String(raw) : ''
}
