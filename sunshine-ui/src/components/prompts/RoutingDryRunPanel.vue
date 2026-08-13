<script setup lang="ts">
import { computed, inject } from 'vue'
import { NButton, NFormItem, NInput, NSelect, NSpin, NTag } from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'
import { shortPromptId } from '../../api/prompts'
import { EXECUTION_MODE_OPTIONS } from '../../api/executionModes'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi

const MODE_LABELS: Record<string, string> = {
  workflow: '工作流',
  fast: '快速',
  pro: '专业',
}

const modeOptions = EXECUTION_MODE_OPTIONS.map(o => ({ label: o.label, value: o.value }))

const STAGE_LABELS: Record<string, string> = {
  rule: '同轨规则命中',
  l3: 'L3 补绑定',
}

const stageLabel = computed(() => {
  const stage = page.dryRunResult?.stage
  if (!stage) return '—'
  return STAGE_LABELS[stage] ?? stage
})

const matchedRuleLabel = computed(() => {
  const id = page.dryRunResult?.matchedRuleId
  if (!id) return '未命中任何规则'
  const item = page.prompts.find(p => p.id === id)
  const shortId = shortPromptId(id)
  if (item?.displayName && item.displayName !== id && item.displayName !== shortId) {
    return `${item.displayName}（${shortId}）`
  }
  return shortId
})

const nextStepLabel = computed(() => {
  if (!page.dryRunResult) return '—'
  if (page.dryRunResult.stage === 'l3') {
    return '未命中同轨规则，将走 L3 补绑定（不改锁定模式）'
  }
  return '已命中同轨规则，按下方执行计划处理'
})

/** 执行计划：适用轨道 · 绑定（workflowId / skill / agent） */
const planLabel = computed(() => {
  const plan = page.dryRunResult?.plan
  if (!plan?.mode) return null
  const mode = plan.mode === 'fast' ? '快速 / 专业' : MODE_LABELS[plan.mode] ?? plan.mode
  const binds: string[] = []
  if (plan.workflowId) binds.push(`工作流 ${plan.workflowId}`)
  const params = plan.params ?? {}
  if (params.skill) binds.push(`绑定技能 ${params.skill}`)
  if (params.agentIds) binds.push(`绑定助手 ${params.agentIds}`)
  if (binds.length === 0) return mode
  return `${mode} · ${binds.join(' · ')}`
})
</script>

<template>
  <main class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-title-block">
        <h3 class="detail-heading">路由试跑</h3>
      </div>
    </div>
    <div class="detail-scroll">
      <div class="mode-row">
        <span class="mode-label">模拟锁定模式</span>
        <NSelect
          v-model:value="page.dryRunMode"
          class="mode-select sun-field"
          :options="modeOptions"
          :consistent-menu-width="false"
        />
      </div>
      <NFormItem label="样例问句" :show-feedback="false">
        <NInput
          v-model:value="page.dryRunQuery"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 10 }"
          placeholder="输入一句用户问题，验证会命中哪条同轨规则"
          @keydown.ctrl.enter="page.runDryRun()"
        />
      </NFormItem>
      <NButton
        size="small"
        round
        type="primary"
        class="action-btn"
        :loading="page.dryRunning"
        @click="page.runDryRun()"
      >
        试跑
      </NButton>

      <NSpin :show="page.dryRunning" size="small">
        <div v-if="page.dryRunResult" class="result-box">
          <div class="result-row">
            <span class="result-label">判定结果</span>
            <NTag size="small" :bordered="false" round>
              {{ stageLabel }}
            </NTag>
          </div>
          <div class="result-row">
            <span class="result-label">命中规则</span>
            <span class="result-value">{{ matchedRuleLabel }}</span>
          </div>
          <div class="result-row">
            <span class="result-label">后续处理</span>
            <span class="result-value">{{ nextStepLabel }}</span>
          </div>
          <div v-if="planLabel" class="result-row">
            <span class="result-label">执行计划</span>
            <span class="result-value">{{ planLabel }}</span>
          </div>
        </div>
        <p v-else class="hint">试跑结果将显示在此处。</p>
      </NSpin>
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
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--sun-border);
  flex-shrink: 0;
}

.detail-title-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.detail-heading {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
}

.detail-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 18px 22px 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.detail-scroll :deep(.n-form-item) {
  margin-bottom: 0;
}

.detail-scroll :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding-bottom: 8px;
}

.mode-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mode-label {
  flex-shrink: 0;
  font-size: 13px;
  color: var(--sun-text-secondary);
  font-weight: 500;
}

.mode-select {
  width: 160px;
}

.result-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
}

.result-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.result-label {
  flex-shrink: 0;
  width: 72px;
  font-size: 13px;
  color: var(--sun-text-secondary);
}

.result-value {
  font-size: 13px;
  color: var(--sun-text);
  word-break: break-all;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.action-btn {
  align-self: flex-start;
  --n-color: var(--sun-accent) !important;
}
</style>
