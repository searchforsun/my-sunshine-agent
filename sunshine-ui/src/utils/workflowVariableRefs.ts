import type { WorkflowPlan, WorkflowPlanNode } from '../api/workflows'

/** 节点输出字段 schema（用于变量引用选择器构建选项树） */
export interface NodeOutputField {
  name: string
  type: string
  /** 嵌套子字段（可选，用于 object 类型展开） */
  children?: NodeOutputField[]
}

/** 各节点类型的静态输出字段 schema；variable-assignment / parameter-extractor 动态推导 */
export const NODE_OUTPUT_SCHEMAS: Record<string, NodeOutputField[]> = {
  start: [{ name: 'userQuery', type: 'string' }],
  rag: [
    { name: 'output', type: 'string' },
    { name: 'hits', type: 'array' },
    { name: 'hitCount', type: 'number' },
  ],
  tool: [
    { name: 'output', type: 'object' },
    { name: 'summary', type: 'string' },
  ],
  agent: [
    { name: 'answer', type: 'string' },
    { name: 'output', type: 'string' },
  ],
  join: [{ name: 'output', type: 'string' }],
  answer: [{ name: 'output', type: 'string' }],
  'variable-assignment': [],
  'parameter-extractor': [],
}

/** 计算给定节点在 DAG 中的所有上游（祖先）节点，按拓扑序返回 */
export function upstreamNodesOf(plan: WorkflowPlan, nodeId: string): WorkflowPlanNode[] {
  const nodes = plan.nodes ?? []
  const edges = plan.edges ?? []
  const nodeById = new Map(nodes.map((n) => [n.id, n]))
  const incoming = new Map<string, string[]>()
  for (const e of edges) {
    incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  }
  const ancIds = new Set<string>()
  const queue = [...(incoming.get(nodeId) ?? [])]
  while (queue.length > 0) {
    const pred = queue.shift()!
    if (!ancIds.add(pred)) continue
    queue.push(...(incoming.get(pred) ?? []))
  }
  // 按节点在 plan.nodes 中的顺序返回（近似拓扑序，保证 start 在前）
  return nodes.filter((n) => ancIds.has(n.id) && nodeById.has(n.id))
}

/** 动态推导 variable-assignment / parameter-extractor 的输出字段名 */
export function dynamicOutputFields(node: WorkflowPlanNode): NodeOutputField[] {
  if (node.type === 'variable-assignment') {
    const assignments = node.params?.assignments
    const list = Array.isArray(assignments)
      ? assignments
      : parseJsonArray(typeof assignments === 'string' ? assignments : '[]')
    return list
      .filter((a): a is Record<string, unknown> => !!a && typeof a === 'object')
      .map((a) => ({
        name: String(a.name ?? '').trim(),
        type: String(a.type ?? 'string').trim() || 'string',
      }))
      .filter((f) => f.name.length > 0)
  }
  if (node.type === 'parameter-extractor') {
    const schema = node.params?.schema
    const obj = parseJsonObject(typeof schema === 'string' ? schema : '')
    return Object.entries(obj)
      .filter(([, v]) => v && typeof v === 'object')
      .map(([name, v]) => {
        const type = String((v as Record<string, unknown>)?.type ?? 'string').toLowerCase()
        return { name, type: ['string', 'number', 'boolean', 'object', 'array'].includes(type) ? type : 'string' }
      })
  }
  return []
}

/** 获取节点输出字段（静态 schema + 动态字段合并） */
export function nodeOutputFields(node: WorkflowPlanNode): NodeOutputField[] {
  const base = NODE_OUTPUT_SCHEMAS[node.type] ?? []
  if (node.type === 'variable-assignment' || node.type === 'parameter-extractor') {
    return dynamicOutputFields(node)
  }
  return base
}

function parseJsonArray(raw: string): Record<string, unknown>[] {
  if (!raw.trim()) return []
  try {
    const obj = JSON.parse(raw)
    return Array.isArray(obj) ? obj.filter((x) => x && typeof x === 'object') : []
  } catch {
    return []
  }
}

function parseJsonObject(raw: string): Record<string, unknown> {
  if (!raw.trim()) return {}
  try {
    const obj = JSON.parse(raw)
    return obj && typeof obj === 'object' && !Array.isArray(obj) ? obj as Record<string, unknown> : {}
  } catch {
    return {}
  }
}
