import type { ToolCatalogEntry } from '../api/tools'
import type { WorkflowPlanNode } from '../api/workflows'

export type ToolOutputMode = 'full' | 'summary' | 'extract'

export interface NodeOutputRef {
  ref: string
  label: string
}

export interface ToolParamField {
  name: string
  description?: string
  required: boolean
}

export const TOOL_OUTPUT_MODE_OPTIONS = [
  { label: '完整输出', value: 'full' as ToolOutputMode },
  { label: '时间线摘要', value: 'summary' as ToolOutputMode },
  { label: '自定义提取', value: 'extract' as ToolOutputMode },
]

const EXTRACT_PRESETS: { label: string; value: string }[] = [
  {
    label: '条数（regex）',
    value: '{"count":"regex:共\\\\s*(\\\\d+)\\\\s*条"}',
  },
  {
    label: '首行（line）',
    value: '{"head":"line:0"}',
  },
  {
    label: '任务 ID 列表（regex）',
    value: '{"firstId":"regex:\\\\[(\\\\d+)\\\\]"}',
  },
]

export function toolOutputMode(params?: Record<string, unknown>): ToolOutputMode {
  const raw = String(params?.['output.mode'] ?? 'full').trim()
  if (raw === 'summary' || raw === 'extract') return raw
  return 'full'
}

export function toolOutputExtract(params?: Record<string, unknown>): string {
  return String(params?.['output.extract'] ?? '').trim()
}

export function parseExtractKeys(extractJson: string): string[] {
  if (!extractJson.trim()) return []
  try {
    const obj = JSON.parse(extractJson) as Record<string, unknown>
    return Object.keys(obj).filter(k => k.trim())
  } catch {
    return []
  }
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

export function nodeOutputRefs(node: WorkflowPlanNode, tool?: ToolCatalogEntry | null): NodeOutputRef[] {
  const id = node.id
  switch (node.type) {
    case 'tool': {
      const refs: NodeOutputRef[] = [
        { ref: `{{${id}.output}}`, label: '完整原始输出' },
      ]
      const mode = toolOutputMode(node.params)
      const hasSummaryTemplate = !!tool?.timelineSummaryTemplate?.trim()
      if (mode === 'summary' || hasSummaryTemplate) {
        refs.push({ ref: `{{${id}.summary}}`, label: '时间线摘要' })
      }
      if (mode === 'extract') {
        for (const key of parseExtractKeys(toolOutputExtract(node.params))) {
          refs.push({ ref: `{{${id}.parsed.${key}}}`, label: `提取字段 ${key}` })
        }
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

export function defaultToolOutputExtract(tool?: ToolCatalogEntry | null): string {
  const raw = tool?.timelineSummaryExtract?.trim()
  return raw || EXTRACT_PRESETS[0].value
}

export function toolExtractPresets(): { label: string; value: string }[] {
  return EXTRACT_PRESETS
}

export function readToolParamValue(params: Record<string, unknown> | undefined, name: string): string {
  const raw = params?.[name]
  return raw != null ? String(raw) : ''
}
