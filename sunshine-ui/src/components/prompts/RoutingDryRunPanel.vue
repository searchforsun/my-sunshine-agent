<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NFormItem, NInput, NSpin, NTag } from 'naive-ui'
import { PROMPTS_PAGE_KEY, type PromptsPageApi } from '../../composables/usePromptsPage'
import { shortPromptId } from '../../api/prompts'

const page = inject(PROMPTS_PAGE_KEY) as PromptsPageApi
</script>

<template>
  <main class="detail-panel">
    <div class="detail-toolbar">
      <div class="detail-title-block">
        <h3 class="detail-heading">路由试跑</h3>
      </div>
    </div>
    <div class="detail-scroll">
      <NFormItem label="样例问句" :show-feedback="false">
        <NInput
          v-model:value="page.dryRunQuery"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 10 }"
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
            <span class="result-value mono">
              {{
                page.dryRunResult.matchedRuleId
                  ? shortPromptId(page.dryRunResult.matchedRuleId)
                  : '（未命中）'
              }}
            </span>
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
