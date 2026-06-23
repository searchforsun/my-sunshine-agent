<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NCard, NTag, NGrid, NGridItem, NButton, NSpace, NDivider, NText } from 'naive-ui'

const MIDDLEWARE_HOST = 'ecs4c16g'

interface ServiceStatus {
  name: string
  host: string
  port: number
  status: 'online' | 'offline' | 'checking' | 'external'
  description: string
  latency?: number
  /** HTTP 健康检查路径；无则浏览器无法探测（标记为 external） */
  healthPath?: string
}

const services = ref<ServiceStatus[]>([
  { name: 'Gateway', host: 'localhost', port: 8000, status: 'checking', description: 'API 网关与路由', healthPath: '/' },
  { name: 'BFF', host: 'localhost', port: 8001, status: 'checking', description: 'SSE 流式转发', healthPath: '/' },
  { name: 'Auth Center', host: 'localhost', port: 8100, status: 'checking', description: 'Sa-Token 认证中心', healthPath: '/' },
  { name: 'Orchestrator', host: 'localhost', port: 8200, status: 'checking', description: 'Agent 编排与 Workflow', healthPath: '/' },
  { name: 'Tool Manager', host: 'localhost', port: 8210, status: 'checking', description: '业务工具注册与 Catalog', healthPath: '/' },
  { name: 'Skill Manager', host: 'localhost', port: 8225, status: 'checking', description: 'Skill 包管理与 Catalog', healthPath: '/' },
  { name: 'LLM Gateway', host: 'localhost', port: 8300, status: 'checking', description: '多厂商大模型路由', healthPath: '/' },
  { name: 'RAG Service', host: 'localhost', port: 8400, status: 'checking', description: 'Milvus 向量检索', healthPath: '/' },
  { name: 'Prompt Manager', host: 'localhost', port: 8500, status: 'checking', description: '提示词模板管理', healthPath: '/' },
  { name: 'Desensitize', host: 'localhost', port: 8600, status: 'checking', description: '数据脱敏引擎', healthPath: '/' },
  { name: 'Finance', host: 'localhost', port: 8710, status: 'checking', description: '财务消息与审批 Mock', healthPath: '/' },
])

const middleware = ref<ServiceStatus[]>([
  { name: 'Nacos', host: MIDDLEWARE_HOST, port: 8848, status: 'checking', description: '注册与配置中心', healthPath: '/nacos/' },
  { name: 'Redis', host: MIDDLEWARE_HOST, port: 6379, status: 'external', description: '缓存与会话' },
  { name: 'MySQL', host: MIDDLEWARE_HOST, port: 3306, status: 'external', description: '关系型数据库' },
  { name: 'MinIO', host: MIDDLEWARE_HOST, port: 9000, status: 'checking', description: 'Skill 包对象存储', healthPath: '/minio/health/live' },
  { name: 'Milvus', host: MIDDLEWARE_HOST, port: 9091, status: 'checking', description: '向量数据库', healthPath: '/healthz' },
  { name: 'RocketMQ', host: MIDDLEWARE_HOST, port: 9876, status: 'external', description: '消息队列' },
  { name: 'Sentinel', host: MIDDLEWARE_HOST, port: 8858, status: 'checking', description: '流量控制面板', healthPath: '/' },
  { name: 'SkyWalking', host: MIDDLEWARE_HOST, port: 8084, status: 'checking', description: '全链路追踪', healthPath: '/' },
  { name: 'Grafana', host: MIDDLEWARE_HOST, port: 3000, status: 'checking', description: '监控可视化', healthPath: '/api/health' },
])

async function probeHttp(item: ServiceStatus) {
  if (!item.healthPath) {
    item.status = 'external'
    item.latency = undefined
    return
  }
  item.status = 'checking'
  const start = Date.now()
  try {
    // no-cors：仅探测 TCP/HTTP 可达，避免各微服务未配 CORS 时误报离线
    await fetch(`http://${item.host}:${item.port}${item.healthPath}`, {
      signal: AbortSignal.timeout(3000),
      mode: 'no-cors',
    })
    item.status = 'online'
    item.latency = Date.now() - start
  } catch {
    item.status = 'offline'
    item.latency = undefined
  }
}

async function checkServices() {
  await Promise.all(services.value.map(probeHttp))
}

async function checkMiddleware() {
  await Promise.all(middleware.value.map(probeHttp))
}

async function refreshAll() {
  await Promise.all([checkServices(), checkMiddleware()])
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
  if (s === 'external') return '远程'
  return '检测中...'
}

function endpoint(item: ServiceStatus) {
  return `${item.host}:${item.port}`
}

const onlineServices = () => services.value.filter(s => s.status === 'online').length
const onlineMiddleware = () =>
  middleware.value.filter(s => s.status === 'online' || s.status === 'external').length

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
          <p>微服务与中间件健康探测（按端口顺序）。</p>
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
        <div class="stat-card">
          <span class="stat-val">{{ onlineMiddleware() }}/{{ middleware.length }}</span>
          <span class="stat-label">中间件在线</span>
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

      <!-- Middleware -->
      <NCard title="中间件" size="medium" class="section-card">
        <NGrid cols="4" x-gap="10" y-gap="10" responsive="screen">
          <NGridItem v-for="mw in middleware" :key="mw.name">
            <NCard size="small" :bordered="true" class="mw-card">
              <div class="mw-top">
                <span class="mw-name">{{ mw.name }}</span>
                <NTag
                  :type="statusType(mw.status)"
                  :bordered="false"
                  size="tiny"
                  round
                >
                  <template v-if="mw.status !== 'external'" #icon>
                    <span
                      class="pulse-dot"
                      :class="mw.status"
                      style="margin-right: 5px"
                    />
                  </template>
                  {{ statusLabel(mw.status) }}
                </NTag>
              </div>
              <div class="mw-body">
                <p class="mw-desc">{{ mw.description }}</p>
                <p class="mw-endpoint">
                  {{ endpoint(mw) }}
                  <template v-if="mw.latency !== undefined && mw.status === 'online'">
                    · {{ mw.latency }}ms
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
.svc-card, .mw-card {
  border-radius: var(--radius-md) !important;
  border-color: var(--sun-border) !important;
  background: var(--sun-deep) !important;
  transition: border-color .2s, transform .15s;
  height: 100%;
}
.svc-card:hover, .mw-card:hover {
  border-color: var(--sun-border-light) !important;
}
.svc-card :deep(.n-card__content),
.mw-card :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
  min-height: 88px;
}

.svc-top, .mw-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.svc-name, .mw-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--sun-text);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.svc-body, .mw-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  margin-top: 8px;
  gap: 8px;
  min-height: 0;
}

.svc-desc, .mw-desc {
  font-size: 11.5px;
  color: var(--sun-text-muted);
  line-height: 1.45;
  margin: 0;
}

.svc-port, .mw-endpoint {
  margin-top: auto;
  margin-bottom: 0;
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* --- Middleware cards --- */
.mw-name {
  font-size: 13px;
}
</style>
