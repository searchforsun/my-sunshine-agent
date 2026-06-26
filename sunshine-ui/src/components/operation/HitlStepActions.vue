<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  hasHitlPanel,
  isHitlAwaiting,
  resolveHitlHint,
  resolveHitlStatus,
  resolveHitlToken,
  resolveHitlToolName,
  type HitlDecision,
} from '../../api/hitlSteps'
import { confirmToolExecution } from '../../api/hitl'

const props = defineProps<{
  step: ProcessingStep
}>()

const emit = defineEmits<{
  decided: [token: string, approved: boolean]
}>()

const loading = ref(false)
const localDecision = ref<HitlDecision | null>(null)

const displayStatus = computed((): HitlDecision | 'awaiting' | null => {
  if (localDecision.value) return localDecision.value
  return resolveHitlStatus(props.step)
})

const showPanel = computed(() => hasHitlPanel(props.step) || localDecision.value != null)
const canAct = computed(() => isHitlAwaiting(props.step) && !localDecision.value && !loading.value)

const toolName = computed(() => resolveHitlToolName(props.step))
const paramsText = computed(() => props.step.metadata?.hitlParamsSummary?.trim() || '')
const hintText = computed(() => resolveHitlHint(props.step))

const statusLabel = computed(() => {
  if (displayStatus.value === 'approved') return '已确认'
  if (displayStatus.value === 'denied') return '已取消'
  return ''
})

async function submit(approved: boolean): Promise<void> {
  const token = resolveHitlToken(props.step)
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
  <div v-if="showPanel" class="hitl-panel" :class="{ 'is-resolved': !!statusLabel }">
    <div class="hitl-body">
      <div class="hitl-info">
        <p class="hitl-tool">{{ toolName }}</p>
        <p v-if="paramsText" class="hitl-params">{{ paramsText }}</p>
        <p v-if="hintText && canAct" class="hitl-hint">{{ hintText }}</p>
        <p v-else-if="statusLabel" class="hitl-status" :class="displayStatus ?? ''">
          {{ statusLabel }}
        </p>
      </div>
      <div v-if="canAct" class="hitl-actions">
        <button type="button" class="hitl-btn hitl-btn-ghost" :disabled="loading" @click="submit(false)">
          取消调用
        </button>
        <button type="button" class="hitl-btn hitl-btn-primary" :disabled="loading" @click="submit(true)">
          {{ loading ? '提交中…' : '确认调用' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hitl-panel {
  margin: 4px 0 2px calc(var(--op-gutter, 12px) + 4px);
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-accent-subtle);
}

.hitl-panel.is-resolved {
  background: color-mix(in srgb, var(--sun-text-muted) 6%, transparent);
}

.hitl-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.hitl-info {
  flex: 1;
  min-width: 0;
}

.hitl-tool {
  margin: 0;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  color: var(--sun-text-secondary);
}

.hitl-params {
  margin: 4px 0 0;
  font-family: var(--sun-font-mono, 'JetBrains Mono', monospace);
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  word-break: break-all;
}

.hitl-hint {
  margin: 6px 0 0;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.hitl-status {
  margin: 6px 0 0;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
}

.hitl-status.approved {
  color: var(--sun-green, #3fb950);
}

.hitl-status.denied {
  color: var(--sun-text-muted);
}

.hitl-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.hitl-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: var(--radius-sm, 6px);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
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
