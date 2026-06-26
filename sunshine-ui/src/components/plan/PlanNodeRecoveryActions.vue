<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { isRecoveryAwaiting, resolveRecoveryError } from '../../api/recoverySteps'
import { confirmWorkflowNodeRecovery } from '../../api/workflowRecovery'

const props = defineProps<{
  step: ProcessingStep
}>()

const emit = defineEmits<{
  decided: [token: string, action: 'retry' | 'terminate' | 'skip']
}>()

const loading = ref(false)
const localAction = ref<'retry' | 'terminate' | 'skip' | null>(null)

const awaiting = computed(() => isRecoveryAwaiting(props.step))
const errorText = computed(() => resolveRecoveryError(props.step))
const token = computed(() => props.step.metadata?.recoveryToken?.trim() ?? '')

async function submit(action: 'retry' | 'terminate' | 'skip') {
  const t = token.value
  if (!t || loading.value || localAction.value) return
  loading.value = true
  try {
    const ok = await confirmWorkflowNodeRecovery(t, action)
    if (ok) {
      localAction.value = action
      emit('decided', t, action)
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-if="awaiting || localAction" class="recovery-panel" :class="{ 'is-resolved': !!localAction }">
    <p class="recovery-title">节点执行失败</p>
    <p v-if="errorText" class="recovery-error">{{ errorText }}</p>
    <p v-if="awaiting && !localAction" class="recovery-hint">可重试该节点、跳过并将错误结果传给下游，或终止整个流程。</p>
    <p v-else-if="localAction === 'retry'" class="recovery-status retry">正在重试…</p>
    <p v-else-if="localAction === 'skip'" class="recovery-status skip">已跳过，继续执行…</p>
    <p v-else-if="localAction === 'terminate'" class="recovery-status terminate">已终止流程</p>
    <div v-if="awaiting && !localAction" class="recovery-actions">
      <button type="button" class="recovery-btn recovery-btn-ghost" :disabled="loading" @click="submit('terminate')">
        终止流程
      </button>
      <button type="button" class="recovery-btn recovery-btn-ghost" :disabled="loading" @click="submit('skip')">
        跳过并继续
      </button>
      <button type="button" class="recovery-btn recovery-btn-primary" :disabled="loading" @click="submit('retry')">
        重试节点
      </button>
    </div>
  </div>
</template>

<style scoped>
.recovery-panel {
  padding: 12px 14px;
  border: 1px solid color-mix(in srgb, #f87171 40%, var(--sun-border));
  border-radius: 8px;
  background: color-mix(in srgb, #f87171 6%, var(--sun-bg));
}

.recovery-panel.is-resolved {
  border-color: var(--sun-border);
  background: color-mix(in srgb, var(--sun-bg) 92%, var(--sun-text-muted));
}

.recovery-title {
  margin: 0 0 6px;
  font-size: var(--sun-font-base);
  font-weight: 600;
  color: var(--sun-text);
}

.recovery-error {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-secondary);
  line-height: 1.45;
  word-break: break-word;
}

.recovery-hint {
  margin: 0 0 10px;
  font-size: var(--sun-font-sm);
  color: var(--sun-text-muted);
}

.recovery-status {
  margin: 0;
  font-size: var(--sun-font-sm);
  font-weight: 500;
}

.recovery-status.retry {
  color: var(--sun-blue, #58a6ff);
}

.recovery-status.skip {
  color: var(--sun-text-secondary);
}

.recovery-status.terminate {
  color: #f87171;
}

.recovery-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.recovery-btn {
  min-width: 88px;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: var(--sun-font-sm);
  font-weight: 500;
  cursor: pointer;
  border: 1px solid transparent;
}

.recovery-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.recovery-btn-ghost {
  border-color: var(--sun-border);
  background: var(--sun-bg);
  color: var(--sun-text-secondary);
}

.recovery-btn-ghost:hover:not(:disabled) {
  background: var(--sun-row-hover);
}

.recovery-btn-primary {
  border-color: color-mix(in srgb, var(--sun-blue, #58a6ff) 55%, var(--sun-border));
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 12%, var(--sun-bg));
  color: var(--sun-blue, #58a6ff);
}

.recovery-btn-primary:hover:not(:disabled) {
  background: color-mix(in srgb, var(--sun-blue, #58a6ff) 20%, var(--sun-bg));
}
</style>
