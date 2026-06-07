<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NCard, NTag, NGrid, NGridItem, NScrollbar, NButton, NSpace, NDivider, NText } from 'naive-ui'

interface ServiceStatus {
  name: string
  port: number
  status: 'online' | 'offline' | 'checking'
  description: string
  latency?: number
}

const services = ref<ServiceStatus[]>([
  { name: 'Gateway', port: 8000, status: 'checking', description: 'API 网关与路由' },
  { name: 'BFF', port: 8001, status: 'checking', description: 'SSE 流式转发' },
  { name: 'Orchestrator', port: 8200, status: 'checking', description: 'AgentScope ReActAgent 编排' },
  { name: 'LLM Gateway', port: 8300, status: 'checking', description: '多厂商大模型路由' },
  { name: 'RAG Service', port: 8400, status: 'checking', description: 'Milvus 向量检索' },
  { name: 'Auth Center', port: 8100, status: 'offline', description: 'Sa-Token 认证中心' },
  { name: 'Tool Manager', port: 8210, status: 'offline', description: '业务工具包装' },
  { name: 'Prompt Manager', port: 8500, status: 'offline', description: '提示词模板管理' },
  { name: 'Desensitize', port: 8600, status: 'offline', description: '数据脱敏引擎' },
])

const middleware = ref<ServiceStatus[]>([
  { name: 'Nacos', port: 8848, status: 'online', description: '注册与配置中心' },
  { name: 'Redis', port: 6379, status: 'online', description: '缓存与会话' },
  { name: 'MySQL', port: 3306, status: 'online', description: '关系型数据库' },
  { name: 'Milvus', port: 19530, status: 'online', description: '向量数据库' },
  { name: 'RocketMQ', port: 9876, status: 'online', description: '消息队列' },
  { name: 'Sentinel', port: 8858, status: 'online', description: '流量控制面板' },
  { name: 'SkyWalking', port: 8084, status: 'online', description: '全链路追踪' },
  { name: 'Grafana', port: 3000, status: 'online', description: '监控可视化' },
])

async function checkServices() {
  for (const svc of services.value) {
    svc.status = 'checking'
    const start = Date.now()
    try {
      const resp = await fetch(`http://localhost:${svc.port}/actuator/health`, {
        signal: AbortSignal.timeout(3000),
      })
      svc.status = resp.ok ? 'online' : 'offline'
      svc.latency = Date.now() - start
    } catch {
      svc.status = 'offline'
    }
  }
}

function statusType(s: string) {
  return s === 'online' ? 'success' : s === 'offline' ? 'error' : 'warning'
}

function statusLabel(s: string) {
  return s === 'online' ? '在线' : s === 'offline' ? '离线' : '检测中...'
}

const onlineServices = () => services.value.filter(s => s.status === 'online').length
const onlineMiddleware = () => middleware.value.filter(s => s.status === 'online').length

onMounted(() => {
  checkServices()
})
</script>

<template>
  <NScrollbar class="status-root">
    <div class="status-content">
      <header class="page-header">
        <div>
          <h2>系统状态</h2>
          <p>微服务与中间件健康状态概览。</p>
        </div>
        <NButton @click="checkServices" size="small" round secondary>
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
              <div class="svc-info">
                <span class="svc-desc">{{ svc.description }}</span>
                <span class="svc-port">
                  :{{ svc.port }}
                  <template v-if="svc.latency !== undefined && svc.status === 'online'">
                    · {{ svc.latency }}ms
                  </template>
                </span>
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
                  :{{ mw.port }}
                </NTag>
              </div>
              <span class="mw-desc">{{ mw.description }}</span>
            </NCard>
          </NGridItem>
        </NGrid>
      </NCard>
    </div>
  </NScrollbar>
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
  color: var(--sun-amber-light);
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
}
.svc-card:hover, .mw-card:hover {
  border-color: var(--sun-border-light) !important;
}

.svc-top, .mw-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.svc-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--sun-text);
}

.svc-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
}

.svc-desc {
  font-size: 11.5px;
  color: var(--sun-text-muted);
  max-width: 65%;
}

.svc-port {
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: 'JetBrains Mono', monospace;
}

/* --- Middleware cards --- */
.mw-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.mw-desc {
  font-size: 11px;
  color: var(--sun-text-muted);
  display: block;
  margin-top: 4px;
}
</style>
