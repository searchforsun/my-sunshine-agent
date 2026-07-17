<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { NButton } from 'naive-ui'
import { resolveHealthProbeUrl } from '../api/config'
import StatusServiceNode from '../status/StatusServiceNode.vue'
import {
  INFRA_ITEMS,
  SERVICE_DEFS,
  buildServiceList,
  countProbeable,
  domainServices,
  entryServices,
  orchestratorServices,
  platformRoots,
  type ServiceDef,
  type ServiceStatus,
} from '../status/statusArchitecture'

interface HealthPayload {
  status?: string
  service?: string
}

const probeSubtitle = '经 Gateway :8000 /health/* 探测，校验 HTTP 200 且 JSON status=UP。'

const services = ref<ServiceStatus[]>(buildServiceList(SERVICE_DEFS))

function byName(name: string): ServiceStatus | undefined {
  return services.value.find((s) => s.name === name)
}

function resolveLaneStatuses(pick: (defs: ServiceDef[]) => ServiceDef[]) {
  return computed(() =>
    pick(SERVICE_DEFS)
      .map((d) => byName(d.name))
      .filter((s): s is ServiceStatus => s != null),
  )
}

const entry = resolveLaneStatuses(entryServices)
const orch = resolveLaneStatuses(orchestratorServices)
const platform = resolveLaneStatuses(platformRoots)
const domain = resolveLaneStatuses(domainServices)
const probeableTotal = countProbeable(SERVICE_DEFS)

const infraLabel = computed(() => INFRA_ITEMS.join(' · '))

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

const onlineServices = () =>
  services.value.filter((s) => s.status === 'online' && !!s.gatewayPath).length

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

      <div class="stats-row">
        <div class="stat-card">
          <span class="stat-val">{{ onlineServices() }}/{{ probeableTotal }}</span>
          <span class="stat-label">微服务在线</span>
        </div>
      </div>

      <section class="arch">
        <div class="lane-label">L0 客户端</div>
        <article class="node node--browser">
          <div class="node-line">
            <span class="node-name">Browser</span>
            <span class="node-meta">sunshine-ui · :5173</span>
          </div>
        </article>

        <div class="lane-arrow">↓</div>

        <div class="lane-label">L1 入口</div>
        <div class="lane-row">
          <StatusServiceNode
            v-for="svc in entry"
            :key="svc.name"
            :item="svc"
          />
        </div>

        <div class="lane-arrow">↓</div>

        <div class="lane-label">L2 编排核心</div>
        <div class="lane-row lane-row--center">
          <StatusServiceNode
            v-for="svc in orch"
            :key="svc.name"
            :item="svc"
            variant="core"
          />
        </div>

        <div class="lane-arrow">↓</div>

        <div class="lane-label">L3 平台能力</div>
        <div class="platform-grid">
          <StatusServiceNode
            v-for="svc in platform"
            :key="svc.name"
            :item="svc"
          />
        </div>

        <div class="lane-arrow">↓</div>

        <div class="lane-label">L4 领域 / 接入</div>
        <div class="lane-row">
          <StatusServiceNode
            v-for="svc in domain"
            :key="svc.name"
            :item="svc"
          />
        </div>

        <div class="lane-arrow">↓</div>

        <div class="lane-label">基础设施</div>
        <div class="infra">{{ infraLabel }}</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.status-root {
  height: 100vh;
  background: var(--sun-black);
}

.status-content {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px;
}

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
  color: var(--sun-text);
}
.page-header p {
  font-size: 13px;
  color: var(--sun-text-muted);
  margin: 2px 0 0;
}

.stats-row {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.stat-card {
  flex: 1;
  background: var(--sun-black);
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

.arch {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  padding: 16px;
  background: var(--sun-black);
}

.lane-label {
  font-size: 11px;
  color: var(--sun-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 8px;
}

.lane-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
}

.lane-row--center {
  justify-content: center;
}

.platform-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

@media (max-width: 900px) {
  .platform-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .platform-grid {
    grid-template-columns: 1fr;
  }
}

.node--browser {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  padding: 8px 12px;
  border-style: dashed;
  text-align: center;
}

.node-line {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  row-gap: 4px;
}

.node-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  white-space: nowrap;
}

.node-meta {
  font-size: 11px;
  font-family: 'JetBrains Mono', monospace;
  color: var(--sun-text-muted);
  white-space: nowrap;
}

.lane-arrow {
  text-align: center;
  color: var(--sun-text-muted);
  margin: 6px 0;
  opacity: 0.5;
}

.infra {
  border: 1px dashed var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px;
  text-align: center;
  color: var(--sun-text-muted);
  font-size: 12px;
}
</style>
