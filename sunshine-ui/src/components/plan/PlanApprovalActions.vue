<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatPlanApprovalRoundSummary,
  isPlanApprovalAwaiting,
  isPlanRegenerating,
  resolvePlanApprovalRounds,
  resolvePlanApprovalToken,
  type PlanApprovalRoundView,
} from '../../api/planApprovalSteps'
import { confirmPlanExecution } from '../../api/planApproval'
import CollapsibleConfirmPanel from '../operation/CollapsibleConfirmPanel.vue'

const props = defineProps<{
  planStep: ProcessingStep
  /** 父级感知重新生成中（点击后 SSE 未到达前） */
  regenerating?: boolean
}>()

const emit = defineEmits<{
  decided: [action: 'approve' | 'regenerate']
}>()

const hint = ref('')
const loading = ref(false)
const localApproved = ref(false)

const rounds = computed(() => {
  const parsed = resolvePlanApprovalRounds(props.planStep)
  if (parsed.length > 0) return parsed
  if (isPlanApprovalAwaiting(props.planStep)) {
    return [{ roundNo: 1, status: 'awaiting' as const }]
  }
  return []
})
const awaiting = computed(() => isPlanApprovalAwaiting(props.planStep) && !localApproved.value)

const isRegenerating = computed(() =>
  props.regenerating || isPlanRegenerating(props.planStep),
)

/** 最后一轮 regenerated 且规划尚未完成 → 进行中，非终态 */
function isRegeneratingRound(round: PlanApprovalRoundView): boolean {
  if (round.status !== 'regenerated' || !isRegenerating.value) return false
  const last = rounds.value[rounds.value.length - 1]
  return last?.roundNo === round.roundNo && last?.status === 'regenerated'
}

function isActiveAwaitingRound(round: PlanApprovalRoundView): boolean {
  return round.status === 'awaiting' && awaiting.value
}

function isRoundResolved(round: PlanApprovalRoundView): boolean {
  return round.status !== 'awaiting' || localApproved.value
}

function roundSummary(round: PlanApprovalRoundView): string {
  if (round.status === 'awaiting' && localApproved.value) {
    return '执行计划确认 · 已确认执行'
  }
  if (round.status === 'awaiting') {
    return '执行计划确认 · 等待确认'
  }
  if (round.status === 'approved') {
    return '执行计划确认 · 已确认执行'
  }
  if (round.status === 'regenerated') {
    if (isRegeneratingRound(round)) {
      return '执行计划确认 · 正在重新生成'
    }
    return '执行计划确认 · 已重新生成'
  }
  if (round.status === 'timed_out') {
    return '执行计划确认 · 确认超时'
  }
  return formatPlanApprovalRoundSummary(round)
}

function roundDetail(round: PlanApprovalRoundView): string {
  if (isRegeneratingRound(round)) return ''
  const chain = round.chainSummary?.trim()
  if (chain) return chain
  if (round.status === 'regenerated' && round.userHint?.trim()) {
    return `修改意见：${round.userHint.trim()}`
  }
  return ''
}

async function approve(): Promise<void> {
  const token = resolvePlanApprovalToken(props.planStep)
  if (!token || loading.value) return
  loading.value = true
  localApproved.value = true
  emit('decided', 'approve')
  try {
    await confirmPlanExecution(token, 'approve')
  } catch {
    localApproved.value = false
  } finally {
    loading.value = false
  }
}

async function regenerate(): Promise<void> {
  const token = resolvePlanApprovalToken(props.planStep)
  if (!token || loading.value) return
  loading.value = true
  emit('decided', 'regenerate')
  try {
    await confirmPlanExecution(token, 'regenerate', hint.value)
    hint.value = ''
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div v-if="rounds.length" class="plan-approval-stack">
    <CollapsibleConfirmPanel
      v-for="round in rounds"
      :key="`plan-round-${round.roundNo}-${round.status}`"
      :summary="roundSummary(round)"
      :detail="roundDetail(round)"
      :resolved="isRoundResolved(round)"
      :default-collapsed="isRoundResolved(round)"
    >
      <template v-if="isActiveAwaitingRound(round)">
        <p v-if="round.chainSummary" class="plan-chain">{{ round.chainSummary }}</p>
        <label class="hint-label" for="plan-mod-hint">修改意见（重新生成时可选）</label>
        <textarea
          id="plan-mod-hint"
          v-model="hint"
          class="hint-input"
          rows="2"
          placeholder="例如：先检索制度，再查待审批，最后合规分析"
          :disabled="loading"
        />
      </template>
      <template v-else>
        <p v-if="round.chainSummary" class="plan-detail">{{ round.chainSummary }}</p>
        <p v-if="round.userHint" class="plan-detail plan-hint-line">修改意见：{{ round.userHint }}</p>
      </template>
      <template v-if="isActiveAwaitingRound(round)" #footer>
        <div class="plan-actions">
          <button type="button" class="plan-btn plan-btn-ghost" :disabled="loading" @click="regenerate">
            重新生成
          </button>
          <button type="button" class="plan-btn plan-btn-primary" :disabled="loading" @click="approve">
            {{ loading ? '提交中…' : '确认执行' }}
          </button>
        </div>
      </template>
    </CollapsibleConfirmPanel>
  </div>
</template>

<style scoped>
.plan-approval-stack {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.plan-chain,
.plan-detail {
  margin: 0 0 6px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
  line-height: 1.45;
}

.plan-hint-line {
  font-style: normal;
}

.hint-label {
  display: block;
  margin-bottom: 4px;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.hint-input {
  width: 100%;
  box-sizing: border-box;
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  font-size: var(--sun-font-sm, 12px);
  font-family: inherit;
  resize: vertical;
  background: transparent;
  color: var(--sun-text);
}

.plan-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.plan-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: var(--radius-sm, 6px);
  font-size: var(--sun-font-sm, 12px);
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}

.plan-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.plan-btn-ghost {
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-secondary);
}

.plan-btn-primary {
  border: 1px solid var(--sun-accent);
  background: var(--sun-accent);
  color: var(--btn-primary-text, #212121);
}
</style>
