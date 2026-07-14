import type { PlanGraph, PlanGraphNode } from '../api/executionPlans'
import { formatPlanNodeType } from '../api/executionPlans'
import type { WorkflowPlan, WorkflowPlanNode, WorkflowNodeDefaultsResponse } from '../api/workflows'
import { buildRetryParams, readRetryMaxAttempts, resolveNodeDefaults } from './workflowNodeParams'
import { fullDagOrder, type DagNodeView } from './planGraph'
import { isGatewayType } from './workflowGateway'

export const WORKFLOW_NODE_TYPES = ['rag', 'tool', 'agent'] as const
export type WorkflowBusinessNodeType = (typeof WORKFLOW_NODE_TYPES)[number]

/** 节点链选中「流程配置」时的哨兵 ID */
export const FLOW_CONFIG_SELECTION = '__flow__'

const DEFAULT_ANSWER_PROMPT = '请根据上游数据回答用户问题。\n\n{{plan.upstream}}'

export function defaultAnswerNode(nodeDefaults?: WorkflowNodeDefaultsResponse): WorkflowPlanNode {
  const params: Record<string, unknown> = { prompt: DEFAULT_ANSWER_PROMPT }
  if (nodeDefaults) {
    Object.assign(params, buildRetryParams('answer', nodeDefaults))
  }
  return {
    id: 'answer',
    type: 'answer',
    displayName: '回答',
    params,
  }
}

export function defaultStartNode(): WorkflowPlanNode {
  return { id: 'start', type: 'start', displayName: '开始', params: {} }
}

export function emptyWorkflowPlan(workflowId: string, nodeDefaults?: WorkflowNodeDefaultsResponse): WorkflowPlan {
  return {
    planId: null,
    reason: `新建工作流 ${workflowId}`,
    nodes: [defaultStartNode(), defaultAnswerNode(nodeDefaults)],
    edges: [{ from: 'start', to: 'answer' }],
  }
}

/** 线性 DAG：start → 业务节点… → answer */
export function rebuildLinearEdges(nodes: WorkflowPlanNode[]): { from: string; to: string }[] {
  const order = nodes.map(n => n.id)
  const edges: { from: string; to: string }[] = []
  for (let i = 0; i < order.length - 1; i++) {
    edges.push({ from: order[i], to: order[i + 1] })
  }
  return edges
}

export function normalizeWorkflowPlan(
  plan: WorkflowPlan,
  workflowId: string,
  nodeDefaults?: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const nodes = [...(plan.nodes ?? [])]
  let start = nodes.find(n => n.type === 'start')
  let answer = nodes.find(n => n.type === 'answer')
  if (!start) {
    start = defaultStartNode()
    nodes.unshift(start)
  }
  if (!answer) {
    answer = defaultAnswerNode(nodeDefaults)
    nodes.push(answer)
  }
  const business = nodes.filter(n => n.type !== 'start' && n.type !== 'answer')
  const normalized: WorkflowPlanNode[] = [start, ...business, answer]
  const edges = (plan.edges?.length ?? 0) > 0
    ? [...plan.edges]
    : rebuildLinearEdges(normalized)
  return {
    planId: plan.planId ?? null,
    reason: plan.reason?.trim() || `工作流 ${workflowId}`,
    nodes: normalized,
    edges,
    layout: plan.layout ? { ...plan.layout } : undefined,
  }
}

export function businessNodeOrder(plan: WorkflowPlan): WorkflowPlanNode[] {
  return (plan.nodes ?? []).filter(n =>
    n.type !== 'start'
    && n.type !== 'answer'
    && !isRoutingNodeType(n.type),
  )
}

/** 路由节点（网关 / join）不参与业务数据链 */
export function isRoutingNodeType(type: string | undefined): boolean {
  return !!type && (isGatewayType(type) || type === 'join')
}

function buildAncestorIds(plan: WorkflowPlan, nodeId: string): Set<string> {
  const incoming = new Map<string, string[]>()
  for (const e of plan.edges ?? []) {
    incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  }
  const anc = new Set<string>()
  const queue = [...(incoming.get(nodeId) ?? [])]
  while (queue.length > 0) {
    const pred = queue.shift()!
    if (!anc.add(pred)) continue
    queue.push(...(incoming.get(pred) ?? []))
  }
  return anc
}

/** 沿 DAG 回溯最近的单一业务前驱（跳过网关 / join） */
function findDataPredecessor(plan: WorkflowPlan, nodeId: string): WorkflowPlanNode | null {
  const edges = plan.edges ?? []
  const nodes = plan.nodes ?? []
  const typeById = new Map(nodes.map(n => [n.id, n.type]))
  const nodeById = new Map(nodes.map(n => [n.id, n]))
  let preds = edges.filter(e => e.to === nodeId).map(e => e.from)
  while (preds.length === 1) {
    const pid = preds[0]
    const ptype = typeById.get(pid)
    if (!ptype || ptype === 'start') return null
    if (isRoutingNodeType(ptype)) {
      preds = edges.filter(e => e.to === pid).map(e => e.from)
      continue
    }
    return nodeById.get(pid) ?? null
  }
  return null
}

/** 发布前校验：至少一个业务节点，否则无法执行 */
export function collectBusinessNodeValidationIssues(plan: WorkflowPlan): string[] {
  if (businessNodeOrder(plan).length === 0) {
    return ['流程须包含至少一个业务节点（知识检索 / 工具 / 智能体）']
  }
  return []
}

export function upstreamOutputRef(node: WorkflowPlanNode): string {
  if (node.type === 'agent') return `{{${node.id}.answer}}`
  return `{{${node.id}.output}}`
}

export function buildDefaultAnswerPrompt(business: WorkflowPlanNode[]): string {
  if (business.length === 0) {
    return '请根据用户问题回答。\n\n用户问题：{{start.userQuery}}'
  }
  const last = business[business.length - 1]
  if (last.type === 'agent') {
    return `请根据分析结果生成用户可见回答。\n\n分析：\n{{${last.id}.answer}}`
  }
  return `请根据上游数据回答用户问题。\n\n上游：\n{{${last.id}.output}}`
}

const SPECIAL_REFS = new Set(['plan.upstream', 'start.userQuery'])

function isBrokenUpstreamRef(
  ref: string | undefined,
  nodeIds: Set<string>,
  typeById?: Map<string, string>,
  consumerId?: string,
  ancestorIds?: Set<string>,
): boolean {
  if (!ref?.trim()) return true
  const matches = [...ref.matchAll(/\{\{([a-zA-Z0-9_.-]+)}}/g)]
  if (matches.length === 0) return false
  for (const m of matches) {
    const path = m[1]
    if (SPECIAL_REFS.has(path) || path.startsWith('plan.params.')) continue
    const dot = path.indexOf('.')
    if (dot < 0) return true
    const nodeId = path.slice(0, dot)
    if (!nodeIds.has(nodeId)) return true
    const producerType = typeById?.get(nodeId)
    if (isRoutingNodeType(producerType)) return true
    if (nodeId !== 'start' && ancestorIds && !ancestorIds.has(nodeId)) return true
  }
  return false
}

/** 拓扑变更后修补默认输入输出引用 */
export function reconcilePlanDataFlow(
  plan: WorkflowPlan,
  options?: { refreshAnswer?: boolean },
): WorkflowPlan {
  const business = businessNodeOrder(plan)
  const nodeIds = new Set((plan.nodes ?? []).map(n => n.id))
  const typeById = new Map((plan.nodes ?? []).map(n => [n.id, n.type]))
  const nodes = (plan.nodes ?? []).map(n => {
    if (n.type === 'agent' || n.type === 'rag') {
      const anc = buildAncestorIds(plan, n.id)
      const prev = findDataPredecessor(plan, n.id)
      const context = n.params?.context
      const nextContext = isBrokenUpstreamRef(String(context ?? ''), nodeIds, typeById, n.id, anc)
        ? (prev ? upstreamOutputRef(prev) : '{{plan.upstream}}')
        : context
      const defaults = defaultParamsForType(n.type)
      return {
        ...n,
        params: {
          ...defaults,
          ...n.params,
          query: n.params?.query ?? '{{start.userQuery}}',
          context: nextContext,
        },
      }
    }
    if (n.type === 'answer') {
      const prompt = n.params?.prompt
      const anc = buildAncestorIds(plan, n.id)
      const nextPrompt = options?.refreshAnswer || isBrokenUpstreamRef(String(prompt ?? ''), nodeIds, typeById, n.id, anc)
        ? buildDefaultAnswerPrompt(business)
        : prompt
      return { ...n, params: { ...n.params, prompt: nextPrompt } }
    }
    return n
  })
  return { ...plan, nodes }
}

export function defaultParamsForType(type: WorkflowBusinessNodeType): Record<string, unknown> {
  switch (type) {
    case 'rag':
      return {
        topK: '3',
        query: '{{start.userQuery}}',
        context: '{{plan.upstream}}',
      }
    case 'tool':
      return { tool: '' }
    case 'agent':
      return {
        query: '{{start.userQuery}}',
        context: '{{plan.upstream}}',
        skill: '',
        tools: '',
        maxIters: '8',
        systemOverlay: '',
      }
    default:
      return {}
  }
}

export function defaultDisplayName(type: WorkflowBusinessNodeType): string {
  switch (type) {
    case 'rag':
      return '知识检索'
    case 'tool':
      return '工具调用'
    case 'agent':
      return '智能体分析'
    default:
      return type
  }
}

export function nextBusinessNodeId(plan: WorkflowPlan, type: WorkflowBusinessNodeType | 'join'): string {
  const prefix = type === 'join' ? 'join' : type
  const ids = new Set((plan.nodes ?? []).map(n => n.id))
  for (let i = 0; i < 32; i++) {
    const suffix = randomNodeSuffix()
    const id = `${prefix}-${suffix}`
    if (!ids.has(id)) return id
  }
  return `${prefix}-${Date.now().toString(36)}`
}

function randomNodeSuffix(): string {
  const bytes = new Uint8Array(4)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
}

/** 根据当前选中节点计算新业务节点插入下标（业务节点数组内） */
export function resolveInsertIndexAfterSelection(
  plan: WorkflowPlan,
  selectedNodeId: string | null,
): number {
  const business = businessNodeOrder(plan)
  if (!selectedNodeId || selectedNodeId === FLOW_CONFIG_SELECTION) return 0
  const selected = (plan.nodes ?? []).find(n => n.id === selectedNodeId)
  if (!selected || selected.type === 'start') return 0
  if (selected.type === 'answer') return business.length
  const idx = business.findIndex(n => n.id === selectedNodeId)
  return idx >= 0 ? idx + 1 : business.length
}

export function insertBusinessNode(
  plan: WorkflowPlan,
  type: WorkflowBusinessNodeType,
  nodeDefaults: WorkflowNodeDefaultsResponse,
  index?: number,
): WorkflowPlan {
  const normalized = normalizeWorkflowPlan(plan, '', nodeDefaults)
  const business = businessNodeOrder(normalized)
  const insertAt = index == null ? business.length : Math.max(0, Math.min(index, business.length))
  const prev = insertAt > 0 ? business[insertAt - 1] : null
  const node: WorkflowPlanNode = {
    id: nextBusinessNodeId(normalized, type),
    type,
    displayName: defaultDisplayName(type),
    params: {
      ...defaultParamsForType(type),
      ...buildRetryParams(type, nodeDefaults),
      ...(type === 'agent' || type === 'rag'
        ? {
            query: '{{start.userQuery}}',
            context: prev ? upstreamOutputRef(prev) : '{{plan.upstream}}',
          }
        : {}),
    },
  }
  business.splice(insertAt, 0, node)
  const after = business[insertAt + 1]
  if (after?.type === 'agent' || after?.type === 'rag') {
    after.params = { ...after.params, context: upstreamOutputRef(node) }
  }
  const existingAnswer = normalized.nodes.find(n => n.type === 'answer') ?? defaultAnswerNode(nodeDefaults)
  const nodes = [defaultStartNode(), ...business, existingAnswer]
  const edges = rebuildLinearEdges(nodes)
  return reconcilePlanDataFlow({ ...normalized, nodes, edges }, { refreshAnswer: true })
}

export function removeBusinessNode(plan: WorkflowPlan, nodeId: string): WorkflowPlan {
  const normalized = normalizeWorkflowPlan(plan, '')
  const business = businessNodeOrder(normalized).filter(n => n.id !== nodeId)
  const existingAnswer = normalized.nodes.find(n => n.type === 'answer') ?? defaultAnswerNode()
  const nodes = [defaultStartNode(), ...business, existingAnswer]
  const edges = rebuildLinearEdges(nodes)
  return reconcilePlanDataFlow({ ...normalized, nodes, edges }, { refreshAnswer: true })
}

export function updateBusinessNode(
  plan: WorkflowPlan,
  nodeId: string,
  patch: Partial<WorkflowPlanNode>,
): WorkflowPlan {
  const nodes = (plan.nodes ?? []).map(n => (n.id === nodeId ? { ...n, ...patch, id: nodeId } : n))
  return { ...plan, nodes }
}

export function buildLinearRagQaPlan(
  workflowId: string,
  nodeDefaults?: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  const ragParams = {
    query: '{{start.userQuery}}',
    topK: '3',
    ...buildRetryParams('rag', resolved),
  }
  const answerPrompt =
    '你是企业制度问答助手。仅根据下方「检索结果」回答，不得编造公司制度。\n\n'
    + '检索结果：\n{{rag-c5d7e903.output}}'
  return {
    planId: null,
    reason: `知识库问答工作流 ${workflowId}`,
    nodes: [
      defaultStartNode(),
      {
        id: 'rag-c5d7e903',
        type: 'rag',
        displayName: '知识检索',
        params: ragParams,
      },
      {
        id: 'answer',
        type: 'answer',
        displayName: '回答',
        params: {
          prompt: answerPrompt,
          ...buildRetryParams('answer', resolved),
        },
      },
    ],
    edges: [
      { from: 'start', to: 'rag-c5d7e903' },
      { from: 'rag-c5d7e903', to: 'answer' },
    ],
  }
}

export function buildLinearToolAgentPlan(
  workflowId: string,
  nodeDefaults?: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  const toolParams = {
    tool: 'sdk__sunshine-finance__list_finance_messages',
    status: '{{plan.params.status}}',
    ...buildRetryParams('tool', resolved),
  }
  const agentParams = {
    query: '{{start.userQuery}}',
    context: '{{tool-d4e8f901.output}}',
    skill: 'finance-analysis',
    tools: 'sdk__sunshine-finance__list_finance_messages',
    maxIters: '4',
    systemOverlay: '本节点仅输出内部分析结论，不面向用户',
    ...buildRetryParams('agent', resolved),
  }
  const answerPrompt =
    '根据 Agent 分析结果生成用户可见回答。\n\n分析：\n{{agent-b2c6d803.answer}}'
  return {
    planId: null,
    reason: `工具 + Agent 分析工作流 ${workflowId}`,
    nodes: [
      defaultStartNode(),
      {
        id: 'tool-d4e8f901',
        type: 'tool',
        displayName: '查询待审批财务消息',
        params: toolParams,
      },
      {
        id: 'agent-b2c6d803',
        type: 'agent',
        displayName: '智能体分析',
        params: agentParams,
      },
      {
        id: 'answer',
        type: 'answer',
        displayName: '回答',
        params: {
          prompt: answerPrompt,
          ...buildRetryParams('answer', resolved),
        },
      },
    ],
    edges: [
      { from: 'start', to: 'tool-d4e8f901' },
      { from: 'tool-d4e8f901', to: 'agent-b2c6d803' },
      { from: 'agent-b2c6d803', to: 'answer' },
    ],
  }
}

export function buildFinanceListPlan(
  workflowId: string,
  nodeDefaults?: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  const toolParams = {
    tool: 'sdk__sunshine-finance__list_finance_messages',
    status: '{{plan.params.status}}',
    ...buildRetryParams('tool', resolved),
  }
  const answerPrompt =
    '根据财务工具查询结果回答用户问题。\n\n'
    + '约束：禁止向用户暴露英文流程/工具内部名。\n\n'
    + '数据：\n{{tool-f7a3b2c1.output}}'
  return {
    planId: null,
    reason: `财务待办查询工作流 ${workflowId}`,
    nodes: [
      defaultStartNode(),
      {
        id: 'tool-f7a3b2c1',
        type: 'tool',
        displayName: '查询待审批财务消息',
        params: toolParams,
      },
      {
        id: 'answer',
        type: 'answer',
        displayName: '回答',
        params: {
          prompt: answerPrompt,
          ...buildRetryParams('answer', resolved),
        },
      },
    ],
    edges: [
      { from: 'start', to: 'tool-f7a3b2c1' },
      { from: 'tool-f7a3b2c1', to: 'answer' },
    ],
  }
}

export function buildFinanceSummaryPlan(
  workflowId: string,
  nodeDefaults?: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  const toolParams = {
    tool: 'sdk__sunshine-finance__summarize_finance_by_status',
    status: '{{plan.params.status}}',
    ...buildRetryParams('tool', resolved),
  }
  const answerPrompt =
    '根据财务汇总工具结果回答用户。\n\n'
    + '约束：禁止向用户暴露英文流程/工具内部名。\n\n'
    + '汇总：\n{{tool-a9c1e502.output}}'
  return {
    planId: null,
    reason: `财务汇总统计工作流 ${workflowId}`,
    nodes: [
      defaultStartNode(),
      {
        id: 'tool-a9c1e502',
        type: 'tool',
        displayName: '统计财务消息',
        params: toolParams,
      },
      {
        id: 'answer',
        type: 'answer',
        displayName: '回答',
        params: {
          prompt: answerPrompt,
          ...buildRetryParams('answer', resolved),
        },
      },
    ],
    edges: [
      { from: 'start', to: 'tool-a9c1e502' },
      { from: 'tool-a9c1e502', to: 'answer' },
    ],
  }
}

export function buildParallelDualRagPlan(
  workflowId: string,
  nodeDefaults?: WorkflowNodeDefaultsResponse,
): WorkflowPlan {
  const resolved = resolveNodeDefaults(nodeDefaults)
  const answerPrompt =
    '你是企业知识助手。综合下方「制度检索」与「财务检索」结果回答，不得编造。\n\n'
    + '制度检索：\n{{rag-a1b2c3d4.output}}\n\n财务检索：\n{{rag-e5f6a7b8.output}}'
  const ragParams = {
    query: '{{start.userQuery}}',
    topK: '3',
    ...buildRetryParams('rag', resolved),
  }
  const joinParams = buildRetryParams('join', resolved)
  const pgParams = buildRetryParams('parallel-gateway', resolved)
  const answerParams = {
    prompt: answerPrompt,
    ...buildRetryParams('answer', resolved),
  }
  return {
    planId: null,
    reason: `并行双检索工作流 ${workflowId}`,
    nodes: [
      defaultStartNode(),
      {
        id: 'pg-a1b2c3d4',
        type: 'parallel-gateway',
        displayName: '并行分叉',
        params: pgParams,
      },
      {
        id: 'rag-a1b2c3d4',
        type: 'rag',
        displayName: '制度检索',
        params: ragParams,
      },
      {
        id: 'rag-e5f6a7b8',
        type: 'rag',
        displayName: '财务检索',
        params: { ...ragParams },
      },
      {
        id: 'join-c9d0e1f2',
        type: 'join',
        displayName: '并行汇总',
        params: joinParams,
      },
      {
        id: 'answer',
        type: 'answer',
        displayName: '回答',
        params: answerParams,
      },
    ],
    edges: [
      { from: 'start', to: 'pg-a1b2c3d4' },
      { from: 'pg-a1b2c3d4', to: 'rag-a1b2c3d4' },
      { from: 'pg-a1b2c3d4', to: 'rag-e5f6a7b8' },
      { from: 'rag-a1b2c3d4', to: 'join-c9d0e1f2' },
      { from: 'rag-e5f6a7b8', to: 'join-c9d0e1f2' },
      { from: 'join-c9d0e1f2', to: 'answer' },
    ],
  }
}

export function isParallelPlan(plan: WorkflowPlan): boolean {
  return (plan.nodes ?? []).some(n => n.type === 'join')
}

export function buildCatalogMeta(
  plan: WorkflowPlan,
  examples: string[],
  intentAfter?: string,
): Record<string, unknown> {
  const nodeSummary = (plan.nodes ?? [])
    .filter(n => n.type !== 'start')
    .map(n => n.type)
  const meta: Record<string, unknown> = { examples, nodeSummary }
  const intent = intentAfter?.trim()
  if (intent) meta.intentAfter = intent
  return meta
}

export function buildPreviewDagNodes(plan: WorkflowPlan): DagNodeView[] {
  const graph: PlanGraph = {
    nodes: plan.nodes as PlanGraphNode[],
    edges: plan.edges,
  }
  const order = fullDagOrder(graph)
  const byId = new Map((plan.nodes ?? []).map(n => [n.id, n]))
  return order.flatMap(id => {
    if (id === 'start') {
      return [{
        id: 'start',
        type: 'start',
        label: '开始',
        status: 'pending' as const,
      }]
    }
    const node = byId.get(id)
    if (!node) return []
    const params = node.params as Record<string, unknown> | undefined
    const retryMax = readRetryMaxAttempts(params, node.type)
    return [{
      id: node.id,
      type: node.type,
      label: node.displayName?.trim() || formatPlanNodeType(node.type),
      status: 'pending' as const,
      retryMaxAttempts: retryMax > 1 ? retryMax : undefined,
    }]
  })
}
