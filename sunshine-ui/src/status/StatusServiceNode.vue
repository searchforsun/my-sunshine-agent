<script setup lang="ts">
import { computed } from 'vue'
import { NTag } from 'naive-ui'
import type { ServiceStatus } from './statusArchitecture'

const props = withDefaults(
  defineProps<{
    item: ServiceStatus
    variant?: 'default' | 'core' | 'child'
  }>(),
  { variant: 'default' },
)

const nodeClass = computed(() => ({
  'node--core': props.variant === 'core',
  'node--child': props.variant === 'child',
}))

function statusType(s: string) {
  if (s === 'online') return 'success'
  if (s === 'offline') return 'error'
  if (s === 'external') return 'info'
  return 'warning'
}

function statusLabel(s: string) {
  if (s === 'online') return '在线'
  if (s === 'offline') return '离线'
  if (s === 'external') return '已接入'
  return '检测中…'
}

function nodeMeta(item: ServiceStatus): string {
  if (item.status === 'online' && item.latency !== undefined) {
    return `${item.latency}ms · :${item.port}`
  }
  if (item.status === 'checking') return '检测中…'
  if (item.status === 'offline') return `离线 · :${item.port}`
  if (!item.gatewayPath || item.port <= 0) return 'Catalog · 无独立端口'
  return `已接入 · :${item.port}`
}
</script>

<template>
  <article class="node" :class="nodeClass">
    <div class="node-line">
      <span class="node-name">{{ item.name }}</span>
      <NTag
        :type="statusType(item.status)"
        :bordered="false"
        size="tiny"
        round
      >
        <template #icon>
          <span class="pulse-dot" :class="item.status" />
        </template>
        {{ statusLabel(item.status) }}
      </NTag>
      <span class="node-meta">{{ nodeMeta(item) }}</span>
    </div>
    <slot />
  </article>
</template>

<style scoped>
.node {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  padding: 8px 12px;
  flex: 1;
  min-width: 0;
}

.node:hover {
  border-color: var(--sun-border-light);
}

.node--core {
  border-color: var(--sun-accent, #5b8def);
  flex: 0 1 auto;
  min-width: 200px;
}

.node--child {
  border-style: dashed;
  font-size: 12px;
  margin-top: 6px;
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

.pulse-dot {
  margin-right: 2px;
}
</style>
