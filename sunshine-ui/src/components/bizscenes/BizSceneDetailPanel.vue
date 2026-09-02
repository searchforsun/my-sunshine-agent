<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NEmpty, NIcon, NTag } from 'naive-ui'
import { AddOutline, TrashOutline } from '@vicons/ionicons5'
import { BIZ_SCENES_PAGE_KEY, type BizScenesPageApi } from '../../composables/useBizScenesPage'

const page = inject(BIZ_SCENES_PAGE_KEY) as BizScenesPageApi

function formatTs(ts?: string | null): string {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ts
  return d.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <main class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-title-block">
        <div class="detail-name-row">
          <h3>{{ page.selectedScene!.bizScene }}</h3>
          <NTag v-if="(page.selectedScene!.source ?? 'manual') === 'auto'" :bordered="false" size="tiny" round class="meta-chip">
            auto
          </NTag>
          <NTag v-if="page.selectedScene!.status === 'pending_review'" :bordered="false" size="tiny" round type="warning" class="meta-chip">
            待审核
          </NTag>
        </div>
        <div class="detail-meta-inline">
          <span class="detail-subtitle">{{ page.selectedScene!.displayName }}</span>
          <span v-if="page.selectedScene!.updatedAt" class="detail-updated">
            更新于 {{ formatTs(page.selectedScene!.updatedAt) }}
          </span>
        </div>
      </div>
    </div>

    <div class="detail-scroll">
      <div class="rules-block">
        <div class="rules-head">
          <span class="rules-title">场景规则</span>
          <NButton v-if="page.selectedScene!.status !== 'pending_review'" size="tiny" secondary class="add-rule-btn" @click="page.openCreateRule()">
            <template #icon><NIcon :component="AddOutline" :size="14" /></template>
            新增规则
          </NButton>
        </div>
        <div class="rules-list">
          <NEmpty v-if="page.sceneRules.length === 0" description="暂无规则" size="small" />
          <div v-for="rule in page.sceneRules" :key="rule.policyId" class="rule-item">
            <div class="rule-item-main">
              <div class="rule-item-head">
                <NTag :bordered="false" size="tiny" class="meta-chip">规则 {{ rule.version }}</NTag>
                <span class="rule-item-time">{{ formatTs(rule.updatedAt) }}</span>
              </div>
              <div class="rule-item-text">{{ rule.rulesJson }}</div>
            </div>
            <NButton
              size="tiny"
              quaternary
              class="rule-delete-btn"
              title="删除规则"
              aria-label="删除规则"
              :loading="page.deleting"
              @click="page.handleDeleteRule(rule)"
            >
              <template #icon><NIcon :component="TrashOutline" :size="14" /></template>
            </NButton>
          </div>
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.detail-panel {
  min-height: 0;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.detail-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px 14px;
  flex-shrink: 0;
}

.detail-title-block {
  min-width: 0;
}

.detail-name-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail-name-row h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--sun-text);
  font-family: 'JetBrains Mono', monospace;
  line-height: 1.3;
  word-break: break-all;
}

.detail-meta-inline {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.detail-subtitle {
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.detail-updated {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 24px 20px;
  overflow: auto;
}

.rules-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  padding-top: 8px;
}

.rules-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.rules-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.add-rule-btn {
  --n-text-color: var(--sun-text-secondary) !important;
}

.rules-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rule-item {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.rule-item-main {
  min-width: 0;
  flex: 1;
}

.rule-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.rule-item-time {
  font-size: 12px;
  color: var(--sun-text-muted);
}

.rule-item-text {
  font-size: 13px;
  color: var(--sun-text);
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}

.rule-delete-btn {
  --n-text-color: var(--sun-text-muted) !important;
  --n-color-hover: var(--sun-row-hover) !important;
  flex-shrink: 0;
}

.rule-delete-btn:hover {
  --n-text-color: #e67e80 !important;
}

.meta-chip {
  --n-color: color-mix(in srgb, var(--sun-text) 8%, var(--sun-black)) !important;
  --n-text-color: var(--sun-text-secondary) !important;
  --n-border: none !important;
  background: color-mix(in srgb, var(--sun-text) 8%, var(--sun-black)) !important;
}
</style>
