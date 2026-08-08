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
  { name: 'Tool Service', port: 8210, description: '工具注册与调用（SDK + MCP）', gatewayPath: '/health/tool-service', expectedService: 'sunshine-tool-service', lane: 'platform' },
  { name: 'Resource Manager', port: 8240, description: '聚合管理（Skill / Agent / Prompt / Desensitize）', gatewayPath: '/health/resource-manager', expectedService: 'sunshine-resource-manager', lane: 'platform' },
  { name: 'Sandbox Service', port: 8226, description: 'Skills Docker 沙箱', gatewayPath: '/health/sandbox', expectedService: 'sunshine-sandbox-service', lane: 'platform' },
  { name: 'Workflow Manager', port: 8230, description: 'Workflow Studio DB / Catalog（4.13）', gatewayPath: '/health/workflow-manager', expectedService: 'sunshine-workflow-manager', lane: 'platform' },
  { name: 'LLM Gateway', port: 8300, description: '多厂商大模型路由', gatewayPath: '/health/llm-gateway', expectedService: 'sunshine-llm-gateway', lane: 'platform' },
  { name: 'RAG Service', port: 8400, description: 'Milvus 向量检索', gatewayPath: '/health/rag', expectedService: 'sunshine-rag', lane: 'platform' },
  { name: 'Biz Simulator', port: 8700, description: '业务模拟聚合（OA / Finance / HR）', gatewayPath: '/health/biz-simulator', expectedService: 'sunshine-biz-simulator', lane: 'domain' },
  { name: 'MCP', port: 0, description: 'MCP 工具接入能力（经 tool-service Catalog，无独立微服务）', lane: 'domain' },
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
