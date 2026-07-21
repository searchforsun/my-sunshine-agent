export type ServiceLane = 'entry' | 'orchestrator' | 'platform' | 'domain'
export type ProbeStatus = 'online' | 'offline' | 'checking' | 'external'

export interface ServiceDef {
  name: string
  port: number
  description: string
  gatewayPath?: string
  expectedService?: string
  lane: ServiceLane
}

export interface ServiceStatus extends ServiceDef {
  status: ProbeStatus
  latency?: number
}

export const INFRA_ITEMS = [
  'Nacos',
  'Redis',
  'MySQL',
  'Milvus',
  'ES',
  'RocketMQ',
  'MinIO',
] as const

export const SERVICE_DEFS: ServiceDef[] = [
  { name: 'Gateway', port: 8000, description: 'API 网关与路由', gatewayPath: '/health', expectedService: 'sunshine-gateway', lane: 'entry' },
  { name: 'BFF', port: 8001, description: 'SSE 流式转发', gatewayPath: '/health/bff', expectedService: 'sunshine-bff', lane: 'entry' },
  { name: 'Auth Center', port: 8100, description: 'Sa-Token 认证中心', gatewayPath: '/health/auth', expectedService: 'sunshine-auth', lane: 'entry' },
  { name: 'Orchestrator', port: 8200, description: 'Agent 编排与 Workflow', gatewayPath: '/health/orchestrator', expectedService: 'sunshine-orchestrator', lane: 'orchestrator' },
  { name: 'Tool Manager', port: 8210, description: '业务工具注册与 Catalog', gatewayPath: '/health/tool-manager', expectedService: 'sunshine-tool-manager', lane: 'platform' },
  { name: 'Skill Manager', port: 8225, description: 'Skill 包管理与 Catalog', gatewayPath: '/health/skill-manager', expectedService: 'sunshine-skill-manager', lane: 'platform' },
  { name: 'Sandbox Service', port: 8226, description: 'Skills Docker 沙箱', gatewayPath: '/health/sandbox', expectedService: 'sunshine-sandbox-service', lane: 'platform' },
  { name: 'Workflow Manager', port: 8230, description: 'Workflow Studio DB / Catalog（4.13）', gatewayPath: '/health/workflow-manager', expectedService: 'sunshine-workflow-manager', lane: 'platform' },
  { name: 'Expert Manager', port: 8235, description: '多专家协作 Catalog / Admin', gatewayPath: '/health/expert-manager', expectedService: 'sunshine-expert-manager', lane: 'platform' },
  { name: 'LLM Gateway', port: 8300, description: '多厂商大模型路由', gatewayPath: '/health/llm-gateway', expectedService: 'sunshine-llm-gateway', lane: 'platform' },
  { name: 'RAG Service', port: 8400, description: 'Milvus 向量检索', gatewayPath: '/health/rag', expectedService: 'sunshine-rag', lane: 'platform' },
  { name: 'Prompt Manager', port: 8500, description: '提示词模板管理', gatewayPath: '/health/prompt', expectedService: 'sunshine-prompt', lane: 'platform' },
  { name: 'Desensitize', port: 8600, description: '数据脱敏引擎', gatewayPath: '/health/desensitize', expectedService: 'sunshine-desensitize', lane: 'platform' },
  { name: 'OA', port: 8700, description: 'OA 模拟 / Tool App', gatewayPath: '/health/oa', expectedService: 'sunshine-oa', lane: 'domain' },
  { name: 'Finance', port: 8710, description: '财务消息与审批 Mock', gatewayPath: '/health/finance', expectedService: 'sunshine-finance', lane: 'domain' },
  { name: 'HR', port: 8720, description: '人事模拟 / Tool App（假期考勤）', gatewayPath: '/health/hr', expectedService: 'sunshine-hr', lane: 'domain' },
  { name: 'MCP', port: 0, description: 'MCP 工具接入能力（经 tool-manager Catalog，无独立微服务）', lane: 'domain' },
]

export function countProbeable(defs: ServiceDef[]): number {
  return defs.filter((d) => !!d.gatewayPath).length
}

export function entryServices(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'entry')
}

export function orchestratorServices(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'orchestrator')
}

export function platformRoots(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'platform')
}

export function domainServices(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'domain')
}

/** L4 上行：可探测的领域 Tool App（OA / Finance / HR） */
export function domainToolApps(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'domain' && !!d.gatewayPath)
}

/** L4 下行：接入能力（MCP，无独立端口） */
export function domainAccess(defs: ServiceDef[]): ServiceDef[] {
  return defs.filter((d) => d.lane === 'domain' && !d.gatewayPath)
}

export function buildServiceList(defs: ServiceDef[]): ServiceStatus[] {
  return defs.map((d) => ({
    ...d,
    status: d.gatewayPath ? 'checking' : 'external',
  }))
}
