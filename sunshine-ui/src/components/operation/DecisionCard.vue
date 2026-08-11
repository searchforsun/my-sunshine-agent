<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { NIcon, NInput } from 'naive-ui'
import { CheckmarkOutline, ChevronDownOutline } from '@vicons/ionicons5'
import type {
  DecisionAnswerView,
  DecisionOptionView,
  DecisionQuestionView,
  ProcessingStep,
} from '../../api/processingSteps'
import { stepLifecycle } from '../../api/processingSteps'
import { resolveDecision } from '../../api/decisions'

const CUSTOM_ID = '__custom__'

const props = withDefaults(defineProps<{
  step: ProcessingStep
  live?: boolean
  generationId?: string
}>(), {
  live: false,
  generationId: '',
})

const decision = computed(() => props.step.metadata?.decision)
const lifecycle = computed(() => stepLifecycle(props.step))
const interactive = computed(() => props.live && lifecycle.value === 'awaiting')
const isResolved = computed(() =>
  lifecycle.value === 'done'
  || lifecycle.value === 'paused'
  || lifecycle.value === 'error'
  || !!decision.value?.outcome
  || !!(decision.value?.answers && decision.value.answers.length > 0),
)

const collapsed = ref(false)
/** questionId → selected option ids */
const selections = reactive<Record<string, string[]>>({})
/** questionId → custom input text */
const customInputs = reactive<Record<string, string>>({})
const loading = ref(false)
const submitError = ref('')
const localSubmitted = ref(false)

watch(
  () => [interactive.value, isResolved.value] as const,
  ([canAct, resolved]) => {
    if (canAct) collapsed.value = false
    else if (resolved) collapsed.value = true
  },
  { immediate: true },
)

watch(
  () => decision.value,
  (meta) => {
    if (!meta?.answers?.length) return
    for (const answer of meta.answers) {
      selections[answer.questionId] = [...(answer.selectedOptionIds ?? [])]
      if (answer.customInput != null) {
        customInputs[answer.questionId] = answer.customInput
      }
    }
  },
  { immediate: true, deep: true },
)

const questions = computed((): DecisionQuestionView[] => decision.value?.questions ?? [])

function optionsForQuestion(question: DecisionQuestionView): DecisionOptionView[] {
  const base = question.options ?? []
  if (base.some(o => o.id === CUSTOM_ID)) return base
  return [...base, { id: CUSTOM_ID, label: '其他（手写）' }]
}

function selectedIds(questionId: string): string[] {
  return selections[questionId] ?? []
}

function isOptionSelected(questionId: string, optionId: string): boolean {
  return selectedIds(questionId).includes(optionId)
}

function needsCustomInput(questionId: string): boolean {
  return isOptionSelected(questionId, CUSTOM_ID)
}

function isQuestionValid(question: DecisionQuestionView): boolean {
  const ids = selectedIds(question.id)
  if (ids.length < 1) return false
  if (!question.allowMultiple && ids.length !== 1) return false
  if (ids.includes(CUSTOM_ID) && !(customInputs[question.id] ?? '').trim()) return false
  return true
}

function toggleOption(question: DecisionQuestionView, optionId: string): void {
  if (!interactive.value || loading.value || localSubmitted.value) return
  submitError.value = ''
  const current = selectedIds(question.id)
  if (question.allowMultiple) {
    selections[question.id] = current.includes(optionId)
      ? current.filter(id => id !== optionId)
      : [...current, optionId]
  } else {
    selections[question.id] = [optionId]
  }
}

function buildAnswers(): DecisionAnswerView[] {
  return questions.value.map((question) => {
    const selectedOptionIds = [...selectedIds(question.id)]
    const answer: DecisionAnswerView = {
      questionId: question.id,
      selectedOptionIds,
    }
    if (selectedOptionIds.includes(CUSTOM_ID)) {
      answer.customInput = (customInputs[question.id] ?? '').trim()
    }
    return answer
  })
}

function formatAnswerLabels(answers: DecisionAnswerView[]): string {
  const parts: string[] = []
  for (const answer of answers) {
    const question = questions.value.find(q => q.id === answer.questionId)
    const opts = question ? optionsForQuestion(question) : []
    const labels = (answer.selectedOptionIds ?? []).map((id) => {
      if (id === CUSTOM_ID) {
        const text = answer.customInput?.trim()
        return text || '其他（手写）'
      }
      return opts.find(o => o.id === id)?.label || id
    })
    if (labels.length) parts.push(labels.join('、'))
  }
  return parts.join('；')
}

const title = computed(() => decision.value?.title?.trim() || '')

const summaryLine = computed(() => {
  if (lifecycle.value === 'paused') {
    return props.step.summary?.after?.trim() || '决策 · 已暂停'
  }
  if (lifecycle.value === 'error') {
    return props.step.summary?.after?.trim() || '决策 · 失败'
  }
  if (isResolved.value || localSubmitted.value) {
    const answers = decision.value?.answers?.length
      ? decision.value.answers
      : (localSubmitted.value ? buildAnswers() : [])
    const labels = formatAnswerLabels(answers)
    return labels ? `决策 · ${labels}` : '决策 · 已提交'
  }
  return props.step.summary?.active?.trim() || '决策 · 等待选择'
})

const collapsedDetail = computed(() => title.value || questions.value.map(q => q.prompt).join(' · '))

const collapsedLine = computed(() => {
  const detail = collapsedDetail.value
  if (!detail) return summaryLine.value
  return `${summaryLine.value} · ${detail}`
})

const canSubmit = computed(() =>
  interactive.value
  && !loading.value
  && !localSubmitted.value
  && !!props.generationId?.trim()
  && !!decision.value?.token?.trim()
  && questions.value.length > 0
  && questions.value.every(isQuestionValid),
)

function toggle(): void {
  collapsed.value = !collapsed.value
}

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  const token = decision.value?.token?.trim()
  if (!token || !props.generationId) return
  loading.value = true
  submitError.value = ''
  try {
    await resolveDecision(props.generationId, token, buildAnswers())
    localSubmitted.value = true
  } catch (e: unknown) {
    const err = e as Error & { body?: { message?: string; key?: string } }
    const key = typeof err.body?.key === 'string' ? err.body.key.trim() : ''
    const message = typeof err.body?.message === 'string' && err.body.message.trim()
      ? err.body.message.trim()
      : (err.message || '提交失败')
    submitError.value = key ? `${message}（${key}）` : message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div
    class="decision-card"
    :class="{
      'is-resolved': isResolved || localSubmitted,
      'is-awaiting': interactive,
      'is-collapsed': collapsed,
      'is-expanded': !collapsed,
    }"
  >
    <div class="decision-shell">
      <button
        type="button"
        class="decision-header"
        :aria-expanded="!collapsed"
        @click="toggle"
      >
        <NIcon
          v-if="!collapsed"
          class="decision-chevron"
          :component="ChevronDownOutline"
          :size="14"
        />
        <svg
          v-else
          class="decision-list-icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
          <rect x="8" y="2" width="8" height="4" rx="1" />
          <path d="m9 14 2 2 4-4" />
        </svg>
        <span v-if="collapsed" class="decision-line">{{ collapsedLine }}</span>
        <span v-else class="decision-summary">{{ summaryLine }}</span>
      </button>

      <div v-show="!collapsed" class="decision-body">
        <p v-if="title" class="decision-title">{{ title }}</p>

        <div
          v-for="question in questions"
          :key="question.id"
          class="decision-question-block"
        >
          <p class="decision-question">{{ question.prompt }}</p>
          <div
            class="decision-options"
            role="listbox"
            :aria-multiselectable="!!question.allowMultiple"
            :aria-label="question.prompt"
          >
            <button
              v-for="opt in optionsForQuestion(question)"
              :key="opt.id"
              type="button"
              role="option"
              class="mode-menu-item decision-option"
              :class="{ 'is-selected': isOptionSelected(question.id, opt.id) }"
              :aria-selected="isOptionSelected(question.id, opt.id)"
              :disabled="!interactive || loading || localSubmitted"
              @click="toggleOption(question, opt.id)"
            >
              <span class="mode-menu-text">
                <span class="mode-menu-title">{{ opt.label }}</span>
              </span>
              <span class="mode-menu-check-slot" aria-hidden="true">
                <NIcon
                  v-if="isOptionSelected(question.id, opt.id)"
                  class="mode-menu-check"
                  :component="CheckmarkOutline"
                  :size="18"
                />
              </span>
            </button>
          </div>

          <div
            v-if="needsCustomInput(question.id) && (interactive || customInputs[question.id])"
            class="decision-input"
          >
            <NInput
              v-model:value="customInputs[question.id]"
              class="sun-field"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              :disabled="!interactive || loading || localSubmitted"
              placeholder=""
            />
          </div>
        </div>

        <p v-if="submitError" class="decision-error">{{ submitError }}</p>

        <div v-if="interactive && !localSubmitted" class="decision-actions">
          <button
            type="button"
            class="hitl-btn hitl-btn-primary"
            :disabled="!canSubmit"
            @click="submit"
          >
            {{ loading ? '提交中…' : '提交决策' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.decision-card {
  margin: 6px 0;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-text-muted);
}

.decision-shell {
  min-width: 0;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-sm, 6px);
  background: var(--sun-black);
}

.decision-header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
}

.decision-card.is-collapsed .decision-header {
  padding: 6px 10px;
  min-height: 28px;
}

.decision-list-icon,
.decision-chevron {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  opacity: 0.72;
  color: var(--sun-text-secondary);
}

.decision-line,
.decision-summary {
  flex: 1;
  min-width: 0;
  line-height: 1.35;
  font-weight: 450;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.decision-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 12px 10px;
}

.decision-title {
  margin: 0;
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.45;
  color: var(--sun-text);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.decision-question-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.decision-question {
  margin: 0;
  font-size: var(--sun-font-base, 14px);
  line-height: 1.45;
  color: var(--sun-text);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.decision-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mode-menu-item.decision-option {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: calc(var(--radius-md, 10px) - 2px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
  transition: background 0.15s, border-color 0.15s, box-shadow 0.15s;
}

.mode-menu-item.decision-option:hover:not(:disabled) {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.mode-menu-item.decision-option.is-selected {
  border-color: var(--sun-accent);
  box-shadow: inset 0 0 0 1px var(--sun-accent);
  background: transparent;
}

.mode-menu-item.decision-option:disabled {
  cursor: default;
  opacity: 1;
}

.mode-menu-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.mode-menu-title {
  font-size: var(--sun-font-base, 14px);
  font-weight: 500;
  line-height: 1.35;
  color: var(--sun-text, #ececec);
}

.mode-menu-check-slot {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 20px;
  min-height: 20px;
}

.mode-menu-check {
  color: var(--sun-text, #ececec);
}

.decision-input {
  min-width: 0;
}

.decision-error {
  margin: 0;
  font-size: var(--sun-font-sm, 12px);
  color: var(--sun-danger, #e85d5d);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.decision-actions {
  display: flex;
  justify-content: flex-end;
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
