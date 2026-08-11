<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { NIcon, NInput } from 'naive-ui'
import { ChevronForwardOutline, HelpCircleOutline } from '@vicons/ionicons5'
import type {
  DecisionAnswerView,
  DecisionOptionView,
  DecisionQuestionView,
  ProcessingStep,
} from '../../api/processingSteps'
import { stepLifecycle } from '../../api/processingSteps'
import { resolveDecision, skipDecision } from '../../api/decisions'

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
/** questionId → selected option ids（不含平台 __custom__；手写走 customInputs） */
const selections = reactive<Record<string, string[]>>({})
/** questionId → 底部固定手写输入 */
const customInputs = reactive<Record<string, string>>({})
const loading = ref(false)
const submitError = ref('')
const localSubmitted = ref(false)
const localOutcome = ref<'answered' | 'skipped' | ''>('')
const pendingAction = ref<'submit' | 'skip' | null>(null)
/** 多题：问卷滚动区；高度 = 第 1 题整块 + 半截第 2 题，引导下滚 */
const questionsScroller = ref<HTMLElement | null>(null)
const questionsMaxHeight = ref<number | null>(null)
const showScrollHint = ref(false)

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
      const ids = (answer.selectedOptionIds ?? []).filter(id => id !== CUSTOM_ID)
      selections[answer.questionId] = ids
      if (answer.customInput != null) {
        customInputs[answer.questionId] = answer.customInput
      } else if ((answer.selectedOptionIds ?? []).includes(CUSTOM_ID)) {
        customInputs[answer.questionId] = answer.customInput ?? ''
      }
    }
  },
  { immediate: true, deep: true },
)

const questions = computed((): DecisionQuestionView[] => decision.value?.questions ?? [])

function updateScrollHint(): void {
  const el = questionsScroller.value
  if (!el || questions.value.length < 2 || questionsMaxHeight.value == null) {
    showScrollHint.value = false
    return
  }
  const canScroll = el.scrollHeight > el.clientHeight + 2
  const atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 4
  showScrollHint.value = canScroll && !atBottom
}

function measureQuestionsViewport(): void {
  if (collapsed.value || questions.value.length < 2) {
    questionsMaxHeight.value = null
    showScrollHint.value = false
    return
  }
  const root = questionsScroller.value
  if (!root) return
  const first = root.querySelector('.decision-question-block') as HTMLElement | null
  if (!first) return
  // 半截下一题：约露出题号行 + 一行选项，提示可继续下滚
  const peekPx = 56
  questionsMaxHeight.value = Math.ceil(first.offsetHeight + peekPx)
  nextTick(updateScrollHint)
}

watch(
  () => [questions.value.length, collapsed.value, interactive.value] as const,
  () => { void nextTick(measureQuestionsViewport) },
)

onMounted(() => {
  void nextTick(measureQuestionsViewport)
  const root = questionsScroller.value
  if (!root || typeof ResizeObserver === 'undefined') return
  const ro = new ResizeObserver(() => measureQuestionsViewport())
  ro.observe(root)
  onBeforeUnmount(() => ro.disconnect())
})

/** 模型选项原样展示（不含平台手写项）；手写固定为底部输入框 */
function choiceOptions(question: DecisionQuestionView): DecisionOptionView[] {
  return (question.options ?? []).filter(o => o.id !== CUSTOM_ID)
}

function optionLetter(index: number): string {
  if (index < 0) return '?'
  if (index < 26) return String.fromCharCode(65 + index)
  return String(index + 1)
}

function selectedIds(questionId: string): string[] {
  return selections[questionId] ?? []
}

function isOptionSelected(questionId: string, optionId: string): boolean {
  return selectedIds(questionId).includes(optionId)
}

function customText(questionId: string): string {
  return (customInputs[questionId] ?? '').trim()
}

function isQuestionValid(question: DecisionQuestionView): boolean {
  const ids = selectedIds(question.id)
  const custom = customText(question.id)
  if (custom) return true
  if (ids.length < 1) return false
  if (!question.allowMultiple && ids.length !== 1) return false
  return true
}

function toggleOption(question: DecisionQuestionView, optionId: string): void {
  if (!interactive.value || loading.value || localSubmitted.value) return
  if (optionId === CUSTOM_ID) return
  submitError.value = ''
  const current = selectedIds(question.id)
  if (question.allowMultiple) {
    selections[question.id] = current.includes(optionId)
      ? current.filter(id => id !== optionId)
      : [...current, optionId]
  } else {
    // 单选：点选字母则清空手写，避免歧义
    customInputs[question.id] = ''
    selections[question.id] = current.includes(optionId) && current.length === 1
      ? []
      : [optionId]
  }
}

function onCustomInput(question: DecisionQuestionView, value: string): void {
  if (!interactive.value || loading.value || localSubmitted.value) return
  submitError.value = ''
  customInputs[question.id] = value
  // 单选：开始手写则清空字母选项
  if (!question.allowMultiple && value.trim()) {
    selections[question.id] = []
  }
}

function isCustomActive(questionId: string): boolean {
  return customText(questionId).length > 0
}

function customLetterIndex(question: DecisionQuestionView): number {
  return choiceOptions(question).length
}

function activateCustom(question: DecisionQuestionView): void {
  if (!interactive.value || loading.value || localSubmitted.value) return
  submitError.value = ''
  if (!question.allowMultiple) {
    selections[question.id] = []
  }
  const el = document.getElementById(`decision-custom-${question.id}`)
  el?.querySelector('input')?.focus()
}

function buildAnswers(): DecisionAnswerView[] {
  return questions.value.map((question) => {
    const custom = customText(question.id)
    let selectedOptionIds = [...selectedIds(question.id)]
    if (custom) {
      if (!question.allowMultiple) {
        selectedOptionIds = [CUSTOM_ID]
      } else if (!selectedOptionIds.includes(CUSTOM_ID)) {
        selectedOptionIds = [...selectedOptionIds, CUSTOM_ID]
      }
    }
    const answer: DecisionAnswerView = {
      questionId: question.id,
      selectedOptionIds,
    }
    if (custom) answer.customInput = custom
    return answer
  })
}

/** 单题答案文案（选项 label / 手写） */
function formatOneAnswerLabel(answer: DecisionAnswerView, question?: DecisionQuestionView): string {
  const opts = question ? choiceOptions(question) : []
  const labels = (answer.selectedOptionIds ?? []).map((id) => {
    if (id === CUSTOM_ID) {
      const text = answer.customInput?.trim()
      return text || '其他（手写）'
    }
    return opts.find(o => o.id === id)?.label || id
  }).filter(Boolean)
  return labels.join('、')
}

const title = computed(() => decision.value?.title?.trim() || '')

const resolvedAnswers = computed((): DecisionAnswerView[] => {
  if (decision.value?.answers?.length) return decision.value.answers
  if (localSubmitted.value && localOutcome.value !== 'skipped') return buildAnswers()
  return []
})

const isSkippedOutcome = computed(() =>
  decision.value?.outcome === 'skipped' || localOutcome.value === 'skipped',
)

/** 折叠两行：问题 / 答案成对（多题多对） */
const collapsedPairs = computed((): Array<{ prompt: string; choice: string }> => {
  if (!(isResolved.value || localSubmitted.value) || isSkippedOutcome.value) return []
  if (lifecycle.value === 'paused' || lifecycle.value === 'error') return []
  const answers = resolvedAnswers.value
  const pairs: Array<{ prompt: string; choice: string }> = []
  if (questions.value.length) {
    for (const question of questions.value) {
      const answer = answers.find(a => a.questionId === question.id)
      const prompt = question.prompt?.trim() || ''
      const choice = answer ? formatOneAnswerLabel(answer, question) : ''
      if (!prompt && !choice) continue
      pairs.push({ prompt, choice })
    }
  } else {
    for (const answer of answers) {
      const choice = formatOneAnswerLabel(answer)
      if (choice) pairs.push({ prompt: '', choice })
    }
  }
  return pairs
})

/** 展开头 / 无答案折叠：短状态行 */
const summaryLine = computed(() => {
  if (lifecycle.value === 'paused') {
    return props.step.summary?.after?.trim() || '决策 · 已暂停'
  }
  if (lifecycle.value === 'error') {
    return props.step.summary?.after?.trim() || '决策 · 失败'
  }
  if (isSkippedOutcome.value) {
    return props.step.summary?.after?.trim() || '决策 · 已跳过'
  }
  if (isResolved.value || localSubmitted.value) {
    return title.value ? `决策 · ${title.value}` : '决策 · 已提交'
  }
  return props.step.summary?.active?.trim() || '决策 · 等待选择'
})

/** 等待折叠：状态 + 标题/问题 */
const collapsedAwaitingLine = computed(() => {
  const awaitingDetail = title.value || questions.value.map(q => q.prompt).filter(Boolean).join(' · ')
  if (!awaitingDetail) return summaryLine.value
  return `${summaryLine.value} · ${awaitingDetail}`
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

const canSkip = computed(() =>
  interactive.value
  && !loading.value
  && !localSubmitted.value
  && !!props.generationId?.trim()
  && !!decision.value?.token?.trim(),
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
  pendingAction.value = 'submit'
  try {
    await resolveDecision(props.generationId, token, buildAnswers())
    localOutcome.value = 'answered'
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
    pendingAction.value = null
  }
}

async function skip(): Promise<void> {
  if (!canSkip.value) return
  const token = decision.value?.token?.trim()
  if (!token || !props.generationId) return
  loading.value = true
  submitError.value = ''
  pendingAction.value = 'skip'
  try {
    await skipDecision(props.generationId, token)
    localOutcome.value = 'skipped'
    localSubmitted.value = true
  } catch (e: unknown) {
    const err = e as Error & { body?: { message?: string; key?: string } }
    const key = typeof err.body?.key === 'string' ? err.body.key.trim() : ''
    const message = typeof err.body?.message === 'string' && err.body.message.trim()
      ? err.body.message.trim()
      : (err.message || '跳过失败')
    submitError.value = key ? `${message}（${key}）` : message
  } finally {
    loading.value = false
    pendingAction.value = null
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
        <span class="decision-toggle-icon" aria-hidden="true">
          <NIcon
            class="decision-help-icon"
            :component="HelpCircleOutline"
            :size="16"
          />
          <NIcon
            class="decision-head-chevron"
            :component="ChevronForwardOutline"
            :size="14"
          />
        </span>
        <div v-if="collapsed" class="decision-collapsed">
          <template v-if="collapsedPairs.length">
            <div
              v-for="(pair, index) in collapsedPairs"
              :key="index"
              class="decision-collapsed-pair"
            >
              <span class="decision-collapsed-q">
                {{ index === 0 ? `决策 · ${pair.prompt || '已提交'}` : pair.prompt }}
              </span>
              <span v-if="pair.choice" class="decision-collapsed-a">{{ pair.choice }}</span>
            </div>
          </template>
          <span v-else class="decision-line">{{ collapsedAwaitingLine }}</span>
        </div>
        <span v-else class="decision-summary">{{ summaryLine }}</span>
      </button>

      <div v-show="!collapsed" class="decision-body">
        <p v-if="title" class="decision-title">{{ title }}</p>

        <div
          ref="questionsScroller"
          class="decision-questions"
          :class="{
            'is-scrollable': questions.length > 1 && questionsMaxHeight != null,
            'has-scroll-hint': showScrollHint,
          }"
          :style="questionsMaxHeight != null ? { maxHeight: `${questionsMaxHeight}px` } : undefined"
          @scroll="updateScrollHint"
        >
          <div
            v-for="(question, qIndex) in questions"
            :key="question.id"
            class="decision-question-block"
          >
            <p class="decision-question">
              <span class="decision-question-index">{{ qIndex + 1 }}.</span>
              {{ question.prompt }}
            </p>

            <div
              class="decision-options"
              role="listbox"
              :aria-multiselectable="!!question.allowMultiple"
              :aria-label="question.prompt"
            >
              <button
                v-for="(opt, optIndex) in choiceOptions(question)"
                :key="opt.id"
                type="button"
                role="option"
                class="decision-choice"
                :class="{ 'is-selected': isOptionSelected(question.id, opt.id) }"
                :aria-selected="isOptionSelected(question.id, opt.id)"
                :disabled="!interactive || loading || localSubmitted"
                @click="toggleOption(question, opt.id)"
              >
                <span class="decision-letter-chip" aria-hidden="true">{{ optionLetter(optIndex) }}</span>
                <span class="decision-choice-label">{{ opt.label }}</span>
              </button>

              <div
                :id="`decision-custom-${question.id}`"
                class="decision-choice decision-choice-custom"
                :class="{ 'is-selected': isCustomActive(question.id) }"
              >
                <button
                  type="button"
                  class="decision-letter-chip"
                  :disabled="!interactive || loading || localSubmitted"
                  :aria-label="`选项 ${optionLetter(customLetterIndex(question))}`"
                  @click="activateCustom(question)"
                >{{ optionLetter(customLetterIndex(question)) }}</button>
                <NInput
                  :value="customInputs[question.id] ?? ''"
                  class="sun-field decision-custom-field"
                  type="text"
                  :disabled="!interactive || loading || localSubmitted"
                  placeholder="自定义..."
                  @update:value="(v: string) => onCustomInput(question, v)"
                  @focus="activateCustom(question)"
                />
              </div>
            </div>
          </div>
        </div>

        <div class="decision-footer">
          <p v-if="submitError" class="decision-error">{{ submitError }}</p>

          <div v-if="interactive && !localSubmitted" class="decision-actions">
            <button
              type="button"
              class="hitl-btn hitl-btn-ghost"
              :disabled="!canSkip"
              @click="skip"
            >
              {{ pendingAction === 'skip' ? '跳过中…' : '跳过' }}
            </button>
            <button
              type="button"
              class="hitl-btn hitl-btn-primary"
              :disabled="!canSubmit"
              @click="submit"
            >
              {{ pendingAction === 'submit' ? '提交中…' : '提交决策' }}
            </button>
          </div>
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
  align-items: flex-start;
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

.decision-toggle-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  margin-top: 1px;
  color: var(--sun-text);
}

.decision-help-icon {
  color: var(--sun-text);
}

.decision-help-icon :deep(svg) {
  color: var(--sun-text);
  opacity: 1;
}

/* hover：? 位切换为 > / ^（折叠 >，展开 ^） */
.decision-head-chevron {
  display: none;
  width: 14px;
  height: 14px;
  box-sizing: border-box;
  color: var(--sun-text-secondary);
  transition: transform 0.15s ease;
}

.decision-header:hover .decision-help-icon {
  display: none;
}

.decision-header:hover .decision-head-chevron {
  display: block;
}

.decision-card.is-collapsed .decision-header:hover .decision-head-chevron {
  transform: rotate(0deg);
}

.decision-card.is-expanded .decision-header:hover .decision-head-chevron {
  transform: rotate(-90deg);
}

.decision-collapsed {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.decision-collapsed-pair {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.decision-collapsed-q {
  min-width: 0;
  line-height: 1.35;
  font-size: var(--sun-font-sm, 12px);
  font-weight: 450;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.decision-collapsed-a {
  min-width: 0;
  line-height: 1.4;
  font-size: var(--sun-font-base, 14px);
  font-weight: 550;
  color: var(--sun-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
  min-height: 0;
}

.decision-title {
  margin: 0;
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 650;
  line-height: 1.4;
  letter-spacing: 0.01em;
  color: var(--sun-text);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.decision-questions {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  min-height: 0;
}

.decision-questions.is-scrollable {
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 2px;
  scrollbar-gutter: stable;
}

.decision-questions.has-scroll-hint {
  -webkit-mask-image: linear-gradient(to bottom, #000 0%, #000 calc(100% - 28px), transparent 100%);
  mask-image: linear-gradient(to bottom, #000 0%, #000 calc(100% - 28px), transparent 100%);
}

.decision-footer {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 2px;
}

.decision-question-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.decision-question {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--sun-text);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.decision-question-index {
  margin-right: 4px;
  font-variant-numeric: tabular-nums;
  color: var(--sun-text-secondary);
}

.decision-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.decision-choice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 4px 2px;
  border: none;
  border-radius: var(--radius-sm, 6px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
}

.decision-choice:hover:not(:disabled) {
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
}

.decision-choice:disabled {
  cursor: default;
}

.decision-choice-custom {
  cursor: default;
  align-items: center;
}

.decision-choice-custom:hover {
  background: transparent;
}

.decision-letter-chip {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  margin: 0;
  padding: 0;
  border: 1px solid var(--sun-border);
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  font-weight: 650;
  line-height: 1;
  color: var(--sun-text);
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}

button.decision-letter-chip:disabled {
  cursor: default;
  opacity: 1;
}

.decision-choice.is-selected .decision-letter-chip {
  border-color: var(--sun-text-secondary);
  background: color-mix(in srgb, var(--sun-text) 6%, transparent);
}

.decision-choice-label {
  flex: 1;
  min-width: 0;
  padding-top: 3px;
  font-size: var(--sun-font-base, 14px);
  font-weight: 450;
  line-height: 1.45;
  color: var(--sun-text);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.decision-custom-field {
  flex: 1;
  min-width: 0;
}

.decision-custom-field :deep(.n-input) {
  --n-border-radius: 6px !important;
  --n-height: 28px !important;
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
  align-items: center;
  gap: 8px;
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
  border-color: var(--sun-border-light, var(--sun-border));
  color: var(--sun-text);
  background: var(--sun-row-hover, rgba(0, 0, 0, 0.04));
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
