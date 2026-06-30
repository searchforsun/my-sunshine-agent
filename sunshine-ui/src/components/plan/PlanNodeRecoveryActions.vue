<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import { formatStepLabel } from '../../api/processingStepsDisplay'
import { isRecoveryAwaiting, resolveRecoveryError } from '../../api/recoverySteps'
import { confirmWorkflowNodeRecovery } from '../../api/workflowRecovery'
import CollapsibleConfirmPanel from '../operation/CollapsibleConfirmPanel.vue'

const props = defineProps<{
  step: ProcessingStep
}>()

const emit = defineEmits<{
  decided: [token: string, action: 'retry' | 'terminate' | 'skip']
}>()

const loading = ref(false)
const localAction = ref<'retry' | 'terminate' | 'skip' | null>(null)

const isPaused = computed(() => props.step.lifecycle === 'paused')
const awaiting = computed(() => isRecoveryAwaiting(props.step) && !isPaused.value)
const showPanel = computed(() => awaiting.value || !!localAction.value || isPaused.value)
const isResolved = computed(() => !!localAction.value)
const errorText = computed(() => resolveRecoveryError(props.step))
const token = computed(() => props.step.metadata?.recoveryToken?.trim() ?? '')

const summaryLine = computed(() => {
  if (isPaused.value) return '节点失败 · 已暂停'
  if (localAction.value === 'retry') return '节点失败：已选择重试'
  if (localAction.value === 'skip') return '节点失败：已跳过并继续'
  if (localAction.value === 'terminate') return '节点失败：已终止流程'
  const label = formatStepLabel(props.step)
  return awaiting.value ? `节点失败：${label} · 等待处理` : `节点失败：${label}`
})

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
  <CollapsibleConfirmPanel
    v-if="showPanel"
    :summary="summaryLine"
    :detail="errorText"
    :resolved="isResolved"
    :default-collapsed="isResolved"
  >
    <p v-if="errorText" class="recovery-error">{{ errorText }}</p>
    <p v-if="awaiting && !localAction" class="recovery-hint">
      可重试该节点、跳过并将错误结果传给下游，或终止整个流程。
    </p>
    <template v-if="awaiting && !localAction" #footer>
      <div class="recovery-actions">
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
    </template>
  </CollapsibleConfirmPanel>
</template>

<style scoped>
.recovery-error {
  margin: 0 0 6px;
  font-size: var(--sun-font-sm, 12px);
  color: #f87171;
  word-break: break-word;
}

.recovery-hint {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.recovery-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.recovery-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: var(--radius-sm, 6px);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}

.recovery-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.recovery-btn-ghost {
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-secondary);
}

.recovery-btn-primary {
  border: 1px solid var(--sun-accent);
  background: var(--sun-accent);
  color: var(--btn-primary-text, #212121);
}
</style>
