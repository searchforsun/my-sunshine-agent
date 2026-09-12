<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { NSpin, NProgress, NButton } from 'naive-ui'
import { fetchSystemInfo, type SystemInfo } from '../api/systemInfo'

const info = ref<SystemInfo | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

let pollTimer: ReturnType<typeof setInterval> | undefined
const POLL_MS = 5_000

async function refresh() {
  loading.value = true
  error.value = null
  try {
    info.value = await fetchSystemInfo()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '服务器信息获取失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void refresh()
  pollTimer = setInterval(() => void refresh(), POLL_MS)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

/** 0..1 → 百分比 */
function pct(v: number): number {
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.min(100, Math.round(v * 100))
}

function toGb(v: number | undefined): string {
  if (v === undefined || !Number.isFinite(v) || v <= 0) return '—'
  return `${(v / 1024 / 1024 / 1024).toFixed(1)} GB`
}

const memoryUsedPct = computed(() => {
  if (!info.value) return 0
  const { used, total } = info.value.memory
  return total > 0 ? Math.round((used / total) * 100) : 0
})

const diskUsedPct = computed(() => {
  if (!info.value) return 0
  const { used, total } = info.value.disk
  return total > 0 ? Math.round((used / total) * 100) : 0
})

const cpuLabel = computed(() => {
  if (!info.value) return '—'
  const load = info.value.cpu.systemLoad
  return load >= 0 ? `${Math.round(load * 100)}%` : '不可用'
})

const osLine = computed(() => {
  if (!info.value) return '—'
  return `${info.value.osName} ${info.value.osVersion} (${info.value.osArch})`
})

const jvmLine = computed(() => {
  if (!info.value) return '—'
  return `${info.value.jvm.name} · JDK ${info.value.jvm.version}`
})

function fmtUptime(ms: number): string {
  const sec = Math.floor(ms / 1000)
  const d = Math.floor(sec / 86400)
  const h = Math.floor((sec % 86400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (d > 0) return `${d}天 ${h}小时 ${m}分钟`
  if (h > 0) return `${h}小时 ${m}分钟`
  return `${m}分钟`
}
</script>

<template>
  <section class="server-panel">
    <div class="server-head">
      <div class="server-title">
        <h3>服务器信息</h3>
        <span v-if="info" class="server-host">宿主机 · {{ info.hostname }} — {{ osLine }}</span>
      </div>
      <NButton size="small" round secondary :loading="loading" @click="refresh">
        刷新
      </NButton>
    </div>

    <NSpin :show="loading">
      <div v-if="error" class="server-error">{{ error }}</div>

      <template v-else-if="info">
        <div class="server-stats">
          <div class="stat-cell">
            <span class="stat-label">CPU 占用</span>
            <span class="stat-value">{{ cpuLabel }}</span>
            <NProgress
              type="line"
              :percentage="pct(info.cpu.systemLoad)"
              :show-indicator="false"
              :height="8"
              :color="info.cpu.systemLoad >= 0 ? '#5b8def' : '#555'"
            />
          </div>
          <div class="stat-cell">
            <span class="stat-label">内存占用</span>
            <span class="stat-value">{{ memoryUsedPct }}%</span>
            <NProgress type="line" :percentage="memoryUsedPct" :show-indicator="false" :height="8" :color="'#5b8def'" />
            <span class="stat-sub">{{ toGb(info.memory.used) }} / {{ toGb(info.memory.total) }}</span>
          </div>
          <div class="stat-cell">
            <span class="stat-label">磁盘占用</span>
            <span class="stat-value">{{ diskUsedPct }}%</span>
            <NProgress type="line" :percentage="diskUsedPct" :show-indicator="false" :height="8" :color="'#8b5cf6'" />
            <span class="stat-sub">{{ toGb(info.disk.used) }} / {{ toGb(info.disk.total) }}</span>
          </div>
        </div>

        <div class="server-meta">
          <div class="meta-row">
            <span class="meta-key">CPU 核心</span>
            <span class="meta-val">{{ info.cpu.cores }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-key">系统负载</span>
            <span class="meta-val">{{ info.cpu.loadAverage >= 0 ? info.cpu.loadAverage.toFixed(2) : '不可用' }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-key">物理内存</span>
            <span class="meta-val">{{ toGb(info.memory.total) }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-key">磁盘总量</span>
            <span class="meta-val">{{ toGb(info.disk.total) }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-key">JVM 运行时</span>
            <span class="meta-val">{{ jvmLine }}</span>
          </div>
          <div class="meta-row">
            <span class="meta-key">JVM 运行时长</span>
            <span class="meta-val">{{ fmtUptime(info.jvm.uptimeMillis) }}</span>
          </div>
        </div>
      </template>

      <div v-else class="server-loading">正在获取服务器信息…</div>
    </NSpin>
  </section>
</template>

<style scoped>
.server-panel {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  padding: 16px;
  background: var(--sun-black);
}

.server-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 14px;
}

.server-title h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--sun-text);
}

.server-host {
  display: inline-block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.server-error {
  padding: 12px;
  color: var(--sun-error, #e54d42);
  font-size: 13px;
}

.server-loading {
  padding: 12px;
  color: var(--sun-text-muted);
  font-size: 13px;
}

.server-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

@media (max-width: 700px) {
  .server-stats {
    grid-template-columns: 1fr;
  }
}

.stat-cell {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.stat-label {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--sun-text);
  letter-spacing: -0.4px;
}

.stat-sub {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-variant-numeric: tabular-nums;
}

.server-meta {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 4px 12px;
}

.meta-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--sun-border);
}

.meta-row:last-child {
  border-bottom: none;
}

.meta-key {
  font-size: 13px;
  color: var(--sun-text-muted);
  flex-shrink: 0;
}

.meta-val {
  font-size: 13px;
  color: var(--sun-text);
  text-align: right;
  font-family: 'JetBrains Mono', monospace;
}
</style>
