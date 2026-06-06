<script setup lang="ts">
import { NCard, NTag, NGrid, NGridItem, NText, NSpace, NScrollbar } from 'naive-ui'

interface ServiceStatus {
  name: string
  port: number
  status: 'online' | 'offline' | 'unknown'
  description: string
}

const services: ServiceStatus[] = [
  { name: 'Gateway', port: 8000, status: 'unknown', description: 'API 网关' },
  { name: 'BFF', port: 8001, status: 'unknown', description: 'SSE 流式转发' },
  { name: 'Auth Center', port: 8100, status: 'unknown', description: 'Sa-Token 认证' },
  { name: 'Orchestrator', port: 8200, status: 'unknown', description: 'AgentScope 编排' },
  { name: 'Tool Manager', port: 8210, status: 'unknown', description: '工具管理' },
  { name: 'LLM Gateway', port: 8300, status: 'unknown', description: '大模型网关' },
  { name: 'RAG Service', port: 8400, status: 'unknown', description: '向量检索' },
  { name: 'Prompt Manager', port: 8500, status: 'unknown', description: '提示词管理' },
  { name: 'Desensitize', port: 8600, status: 'unknown', description: '数据脱敏' },
  { name: 'OA Mock', port: 8700, status: 'unknown', description: 'OA 模拟' },
  { name: 'Finance Mock', port: 8710, status: 'unknown', description: '财务模拟' },
]

const middleware: ServiceStatus[] = [
  { name: 'Nacos', port: 8848, status: 'online', description: '注册中心' },
  { name: 'Redis', port: 6379, status: 'online', description: '缓存' },
  { name: 'MySQL', port: 3306, status: 'online', description: '数据库' },
  { name: 'Milvus', port: 19530, status: 'online', description: '向量数据库' },
  { name: 'RocketMQ', port: 9876, status: 'online', description: '消息队列' },
  { name: 'SkyWalking', port: 11800, status: 'online', description: '链路追踪' },
  { name: 'Sentinel', port: 8858, status: 'online', description: '流量控制' },
  { name: 'Grafana', port: 3000, status: 'online', description: '监控大盘' },
]

function statusColor(s: string) {
  return s === 'online' ? 'success' : s === 'offline' ? 'error' : 'default'
}
</script>

<template>
  <NScrollbar style="height: 100vh">
    <div style="padding: 24px; max-width: 1000px; margin: 0 auto">
      <div style="font-size: 16px; font-weight: 600; margin-bottom: 24px">系统状态</div>

      <NCard title="🖥️ 微服务" style="margin-bottom: 24px">
        <NGrid cols="3" x-gap="12" y-gap="12">
          <NGridItem v-for="svc in services" :key="svc.name">
            <NCard size="small">
              <NSpace justify="space-between" align="center">
                <div>
                  <div style="font-weight: 500; font-size: 14px">{{ svc.name }}</div>
                  <NText depth="3" style="font-size: 11px">{{ svc.description }}</NText>
                </div>
                <NTag :type="statusColor(svc.status)" size="small" :bordered="false">
                  :{{ svc.port }}
                </NTag>
              </NSpace>
            </NCard>
          </NGridItem>
        </NGrid>
      </NCard>

      <NCard title="🏗️ 中间件">
        <NGrid cols="3" x-gap="12" y-gap="12">
          <NGridItem v-for="mw in middleware" :key="mw.name">
            <NCard size="small">
              <NSpace justify="space-between" align="center">
                <div>
                  <div style="font-weight: 500; font-size: 14px">{{ mw.name }}</div>
                  <NText depth="3" style="font-size: 11px">{{ mw.description }}</NText>
                </div>
                <NTag :type="statusColor(mw.status)" size="small" :bordered="false">
                  :{{ mw.port }} {{ mw.status === 'online' ? '✓' : '' }}
                </NTag>
              </NSpace>
            </NCard>
          </NGridItem>
        </NGrid>
      </NCard>
    </div>
  </NScrollbar>
</template>
