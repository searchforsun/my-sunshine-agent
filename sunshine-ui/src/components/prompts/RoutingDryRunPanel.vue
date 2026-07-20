<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NFormItem, NInput, NSpin, NTag } from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi
</script>

<template>
  <section class="dry-run-panel">
    <header class="panel-head">
      <h4 class="panel-title">路由试跑</h4>
    </header>
    <div class="panel-body">
      <NFormItem label="样例问句" :show-feedback="false">
        <NInput
          v-model:value="page.dryRunQuery"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="输入一句用户问题，验证会命中哪条规则"
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
            <span class="result-label">stage</span>
            <NTag size="small" :bordered="false">{{ page.dryRunResult.stage || '—' }}</NTag>
          </div>
          <div class="result-row">
            <span class="result-label">matchedRuleId</span>
            <span class="result-value mono">{{ page.dryRunResult.matchedRuleId || '（未命中）' }}</span>
          </div>
          <div class="result-row">
            <span class="result-label">wouldLlm</span>
            <span class="result-value">{{ page.dryRunResult.wouldLlm ? '是（将走 L3）' : '否' }}</span>
          </div>
          <div v-if="page.dryRunResult.plan" class="result-row">
            <span class="result-label">plan</span>
            <span class="result-value mono">
              {{ page.dryRunResult.plan.mode }}
              <template v-if="page.dryRunResult.plan.workflowId">
                · {{ page.dryRunResult.plan.workflowId }}
              </template>
            </span>
          </div>
        </div>
        <p v-else class="hint">试跑结果将显示命中规则与是否落入 LLM 意图分类。</p>
      </NSpin>
    </div>
  </section>
</template>

<style scoped>
.dry-run-panel {
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.panel-head {
  padding: 14px 16px;
  border-bottom: 1px solid var(--sun-border);
}

.panel-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.panel-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-body :deep(.n-form-item) {
  margin-bottom: 0;
}

.panel-body :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  padding-bottom: 8px;
}

.result-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
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
  width: 110px;
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
}

.result-value {
  font-size: 13px;
  color: var(--sun-text);
  word-break: break-all;
}

.mono {
  font-family: var(--sun-font-mono, monospace);
  font-size: 12px;
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
