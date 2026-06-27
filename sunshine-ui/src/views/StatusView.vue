<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NCard, NTag, NGrid, NGridItem, NButton } from 'naive-ui'
import { resolveHealthProbeUrl } from '../api/config'

interface HealthPayload {
  status?: string
  service?: string
}

interface ServiceStatus {
  name: string
  /** 原服务端口，仅展示 */
  port: number
  status: 'online' | 'offline' | 'checking' | 'external'
  description: string
  latency?: number
  /** Gateway 路由路径；无则表示未暴露经 Gateway，不可探测 */
  gatewayPath?: string
  /** 期望 JSON 中的 service 字段，用于防路由误配 */
  expectedService?: string
}

/** 经 Gateway :8000 路由探测，不直连各微服务端口 */
const SERVICE_DEFS: Omit<ServiceStatus, 'status'>[] = [
  { name: 'Gateway', port: 8000, description: 'API 网关与路由', gatewayPath: '/health', expectedService: 'sunshine-gateway' },
  { name: 'BFF', port: 8001, description: 'SSE 流式转发', gatewayPath: '/health/bff', expectedService: 'sunshine-bff' },
  { name: 'Auth Center', port: 8100, description: 'Sa-Token 认证中心', gatewayPath: '/health/auth', expectedService: 'sunshine-auth' },
  { name: 'Orchestrator', port: 8200, description: 'Agent 编排与 Workflow', gatewayPath: '/health/orchestrator', expectedService: 'sunshine-orchestrator' },
  { name: 'Tool Manager', port: 8210, description: '业务工具注册与 Catalog', gatewayPath: '/health/tool-manager', expectedService: 'sunshine-tool-manager' },
  { name: 'Skill Manager', port: 8225, description: 'Skill 包管理与 Catalog', gatewayPath: '/health/skill-manager', expectedService: 'sunshine-skill-manager' },
  { name: 'LLM Gateway', port: 8300, description: '多厂商大模型路由', gatewayPath: '/health/llm-gateway', expectedService: 'sunshine-llm-gateway' },
  { name: 'RAG Service', port: 8400, description: 'Milvus 向量检索', gatewayPath: '/health/rag', expectedService: 'sunshine-rag' },
  { name: 'Prompt Manager', port: 8500, description: '提示词模板管理', gatewayPath: '/health/prompt', expectedService: 'sunshine-prompt' },
  { name: 'Desensitize', port: 8600, description: '数据脱敏引擎', gatewayPath: '/health/desensitize', expectedService: 'sunshine-desensitize' },
  { name: 'Finance', port: 8710, description: '财务消息与审批 Mock', gatewayPath: '/health/finance', expectedService: 'sunshine-finance' },
]

function buildList(defs: Omit<ServiceStatus, 'status'>[]): ServiceStatus[] {
  return defs.map((d) => ({
    ...d,
    status: d.gatewayPath ? 'checking' : 'external',
  }))
}

const services = ref<ServiceStatus[]>(buildList(SERVICE_DEFS))

const probeSubtitle = '经 Gateway :8000 /health/* 探测，校验 HTTP 200 且 JSON status=UP。'

function resolveProbeUrl(item: ServiceStatus): string | null {
  if (!item.gatewayPath) return null
  return resolveHealthProbeUrl(item.gatewayPath)
}

function isHealthyPayload(body: HealthPayload, expectedService?: string): boolean {
  if (body.status !== 'UP') return false
  if (expectedService && body.service !== expectedService) return false
  return true
}

async function probeHttp(item: ServiceStatus) {
  const url = resolveProbeUrl(item)
  if (!url) {
    item.status = 'external'
    item.latency = undefined
    return
  }
  item.status = 'checking'
  const start = Date.now()
  try {
    const res = await fetch(url, {
      signal: AbortSignal.timeout(5000),
      headers: { Accept: 'application/json' },
    })
    const latency = Date.now() - start
    if (!res.ok) {
      item.status = 'offline'
      item.latency = undefined
      return
    }
    const body = (await res.json()) as HealthPayload
    if (isHealthyPayload(body, item.expectedService)) {
      item.status = 'online'
      item.latency = latency
    } else {
      item.status = 'offline'
      item.latency = undefined
    }
  } catch {
    item.status = 'offline'
    item.latency = undefined
  }
}

async function checkServices() {
  await Promise.all(services.value.map(probeHttp))
}

async function refreshAll() {
  await checkServices()
}

function statusType(s: string) {
  if (s === 'online') return 'success'
  if (s === 'offline') return 'error'
  if (s === 'external') return 'info'
  return 'warning'
}

function statusLabel(s: string) {
  if (s === 'online') return '在线'
  if (s === 'offline') return '离线'
  if (s === 'external') return '内网'
  return '检测中...'
}

function endpoint(item: ServiceStatus) {
  if (item.gatewayPath) {
    return `:8000${item.gatewayPath}`
  }
  return `:${item.port}（内网）`
}

const onlineServices = () => services.value.filter(s => s.status === 'online').length

onMounted(() => {
  refreshAll()
})
</script>

<template>
  <div class="status-root">
    <div class="status-content">
      <header class="page-header">
        <div>
          <h2>系统状态</h2>
          <p>{{ probeSubtitle }}</p>
        </div>
        <NButton @click="refreshAll" size="small" round secondary>
          刷新
        </NButton>
      </header>

      <!-- Summary Stats -->
      <div class="stats-row">
        <div class="stat-card">
          <span class="stat-val">{{ onlineServices() }}/{{ services.length }}</span>
          <span class="stat-label">微服务在线</span>
        </div>
      </div>

      <!-- Microservices -->
      <NCard title="微服务" size="medium" class="section-card">
        <NGrid cols="3" x-gap="10" y-gap="10" responsive="screen">
          <NGridItem v-for="svc in services" :key="svc.name">
            <NCard size="small" :bordered="true" class="svc-card">
              <div class="svc-top">
                <span class="svc-name">{{ svc.name }}</span>
                <NTag
                  :type="statusType(svc.status)"
                  :bordered="false"
                  size="tiny"
                  round
                >
                  <template #icon>
                    <span
                      class="pulse-dot"
                      :class="svc.status"
                      style="margin-right: 5px"
                    />
                  </template>
                  {{ statusLabel(svc.status) }}
                </NTag>
              </div>
              <div class="svc-body">
                <p class="svc-desc">{{ svc.description }}</p>
                <p class="svc-port">
                  {{ endpoint(svc) }}
                  <template v-if="svc.latency !== undefined && svc.status === 'online'">
                    · {{ svc.latency }}ms
                  </template>
                </p>
              </div>
            </NCard>
          </NGridItem>
        </NGrid>
      </NCard>
    </div>
  </div>
</template>

<style scoped>
.status-root {
  height: 100vh;
}

.status-content {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

/* --- Header --- */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}
.page-header h2 {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  margin: 0;
}
.page-header p {
  font-size: 13px;
  color: var(--sun-text-muted);
  margin: 2px 0 0;
}

/* --- Stats --- */
.stats-row {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.stat-card {
  flex: 1;
  background: var(--sun-surface);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
}

.stat-val {
  font-size: 26px;
  font-weight: 700;
  color: var(--sun-text);
  letter-spacing: -0.5px;
  font-family: 'JetBrains Mono', monospace;
}

.stat-label {
  font-size: 12px;
  color: var(--sun-text-muted);
  margin-top: 2px;
}

/* --- Section cards --- */
.section-card {
  margin-bottom: 20px;
  border-radius: var(--radius-lg) !important;
  border: 1px solid var(--sun-border) !important;
  background: var(--sun-surface) !important;
}

/* --- Service cards --- */
.svc-card {
  border-radius: var(--radius-md) !important;
  border-color: var(--sun-border) !important;
  background: var(--sun-deep) !important;
  transition: border-color .2s, transform .15s;
  height: 100%;
}
.svc-card:hover {
  border-color: var(--sun-border-light) !important;
}
.svc-card :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
  min-height: 88px;
}

.svc-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.svc-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--sun-text);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.svc-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  margin-top: 8px;
  gap: 8px;
  min-height: 0;
}

.svc-desc {
  font-size: 11.5px;
  color: var(--sun-text-muted);
  line-height: 1.45;
  margin: 0;
}

.svc-port {
  margin-top: auto;
  margin-bottom: 0;
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
