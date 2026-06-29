<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  hasHitlPanel,
  hitlConfirmationForStep,
  isHitlAwaiting,
  isHitlSummaryAwaiting,
  resolveHitlStatus,
  resolveHitlToken,
  resolveHitlToolName,
  parseHitlParamsSummary,
  resolveStepForHitlDisplay,
  isHitlToolStep,
  type HitlConfirmationPayload,
  type HitlDecision,
} from '../../api/hitlSteps'
import { confirmToolExecution } from '../../api/hitl'
import CollapsibleConfirmPanel from './CollapsibleConfirmPanel.vue'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  /** confirmation 早于 step.metadata 时的 SSE 载荷 */
  pendingConfirmation?: HitlConfirmationPayload
}>(), {
  pendingConfirmation: undefined,
})

const emit = defineEmits<{
  decided: [token: string, approved: boolean]
}>()

const loading = ref(false)
const localDecision = ref<HitlDecision | null>(null)

const displayStep = computed(() =>
  resolveStepForHitlDisplay(props.step, props.pendingConfirmation),
)

const effectiveToken = computed(() =>
  resolveHitlToken(displayStep.value)
  ?? props.pendingConfirmation?.confirmationToken?.trim()
  ?? null,
)

const displayStatus = computed((): HitlDecision | 'awaiting' | null => {
  if (localDecision.value) return localDecision.value
  return resolveHitlStatus(displayStep.value)
})

const isPaused = computed(() => props.step.lifecycle === 'paused')

const canAct = computed(() => {
  if (isPaused.value || localDecision.value || loading.value) return false
  if (!effectiveToken.value) return false
  if (resolveHitlStatus(displayStep.value) === 'approved' || resolveHitlStatus(displayStep.value) === 'denied') {
    return false
  }
  return isHitlAwaiting(displayStep.value)
    || isHitlSummaryAwaiting(props.step)
})
const isResolved = computed(() =>
  !!localDecision.value || displayStatus.value === 'approved' || displayStatus.value === 'denied',
)

/** metadata / summary 等待 / pending confirmation 任一满足即展示 */
const showPanel = computed(() => {
  if (localDecision.value) return true
  if (hasHitlPanel(displayStep.value)) return true
  if (isHitlSummaryAwaiting(props.step)) return true
  if (hitlConfirmationForStep(props.step, props.pendingConfirmation)) return true
  const active = props.step.summary?.active?.trim() ?? ''
  return isHitlToolStep(props.step)
    && active.includes('等待')
    && active.includes('确认')
})

const toolName = computed(() => resolveHitlToolName(displayStep.value))
const paramPairs = computed(() =>
  parseHitlParamsSummary(displayStep.value.metadata?.hitlParamsSummary),
)

const summaryLine = computed(() => {
  if (isPaused.value) return '写操作确认 · 已暂停'
  if (displayStatus.value === 'approved') return '写操作确认 · 已确认'
  if (displayStatus.value === 'denied') return '写操作确认 · 已取消'
  return '写操作确认 · 等待确认'
})

const collapsedDetail = computed(() => toolName.value)

async function submit(approved: boolean): Promise<void> {
  const token = effectiveToken.value
  if (!token || loading.value || localDecision.value) return
  loading.value = true
  localDecision.value = approved ? 'approved' : 'denied'
  emit('decided', token, approved)
  try {
    await confirmToolExecution(token, approved)
  } catch {
    localDecision.value = null
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <CollapsibleConfirmPanel
    v-if="showPanel"
    :summary="summaryLine"
    :detail="collapsedDetail"
    :resolved="isResolved"
    :default-collapsed="isResolved"
  >
    <p class="hitl-tool">{{ toolName }}</p>
    <dl v-if="paramPairs.length" class="hitl-params">
      <template v-for="pair in paramPairs" :key="pair.key">
        <dt>{{ pair.key }}</dt>
        <dd>{{ pair.value }}</dd>
      </template>
    </dl>
    <template v-if="canAct" #footer>
      <div class="hitl-actions hitl-actions-footer">
        <button type="button" class="hitl-btn hitl-btn-ghost" :disabled="loading" @click="submit(false)">
          取消调用
        </button>
        <button type="button" class="hitl-btn hitl-btn-primary" :disabled="loading" @click="submit(true)">
          {{ loading ? '提交中…' : '确认调用' }}
        </button>
      </div>
    </template>
  </CollapsibleConfirmPanel>
</template>

<style scoped>
.hitl-tool {
  margin: 0 0 6px;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  color: var(--sun-text-secondary);
}

.hitl-params {
  margin: 0 0 6px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.hitl-params dt {
  margin: 6px 0 2px;
  font-weight: 500;
  color: var(--sun-text-secondary);
}

.hitl-params dd {
  margin: 0;
  font-family: var(--sun-font-mono, 'JetBrains Mono', monospace);
  word-break: break-all;
}

.hitl-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.hitl-actions-footer {
  justify-content: flex-end;
  width: 100%;
}

.hitl-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: var(--radius-sm, 6px);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}

.hitl-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.hitl-btn-ghost {
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-secondary);
}

.hitl-btn-ghost:hover:not(:disabled) {
  border-color: var(--sun-border-light);
  color: var(--sun-text);
}

.hitl-btn-primary {
  border: 1px solid var(--sun-accent);
  background: var(--sun-accent);
  color: var(--btn-primary-text, #212121);
}

.hitl-btn-primary:hover:not(:disabled) {
  background: var(--sun-accent-hover);
  border-color: var(--sun-accent-hover);
}
</style>
