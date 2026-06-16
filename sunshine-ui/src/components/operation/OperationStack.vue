<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import OperationCard from './OperationCard.vue'

const props = defineProps<{
  steps: ProcessingStep[]
  live?: boolean
}>()

const cardExpanded = reactive(new Map<string, boolean>())
const cardUserToggled = reactive(new Set<string>())

function lifecycleOf(step: ProcessingStep) {
  return step.lifecycle ?? step.status ?? 'pending'
}

function stepHasBody(step: ProcessingStep): boolean {
  const before = step.summary?.before?.trim()
  const active = step.summary?.active?.trim()
  const after = step.summary?.after?.trim()
  return !!(
    before
    || active
    || after
    || step.reasoning?.trim()
    || step.output?.trim()
  )
}

/** 默认展开：running 或含详情；用户可手动折叠已完成步骤 */
function isCardExpanded(step: ProcessingStep): boolean {
  if (cardUserToggled.has(step.id)) {
    return cardExpanded.get(step.id) ?? true
  }
  if (lifecycleOf(step) === 'running') return true
  return stepHasBody(step)
}

function toggleCard(step: ProcessingStep): void {
  cardUserToggled.add(step.id)
  cardExpanded.set(step.id, !isCardExpanded(step))
}

watch(
  () => props.steps,
  (steps) => {
    for (const step of steps) {
      if (!cardUserToggled.has(step.id) && lifecycleOf(step) === 'running') {
        cardExpanded.set(step.id, true)
      }
    }
  },
  { immediate: true, deep: true },
)
</script>

<template>
  <div class="operation-lines">
    <OperationCard
      v-for="step in steps"
      :key="step.id"
      :step="step"
      :expanded="isCardExpanded(step)"
      :live="live && lifecycleOf(step) === 'running'"
      @toggle="toggleCard(step)"
    />
  </div>
</template>

<style scoped>
.operation-lines {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 0 8px;
  margin-left: -2px;
}
</style>
