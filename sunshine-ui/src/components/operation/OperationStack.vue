<script setup lang="ts">
import { reactive } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import OperationCard from './OperationCard.vue'

defineProps<{
  steps: ProcessingStep[]
  live?: boolean
}>()

const cardExpanded = reactive(new Map<string, boolean>())
const cardUserToggled = reactive(new Set<string>())

function lifecycleOf(step: ProcessingStep) {
  return step.lifecycle ?? step.status ?? 'pending'
}

/** 默认折叠；用户手动展开/折叠后记住选择 */
function isCardExpanded(step: ProcessingStep): boolean {
  if (cardUserToggled.has(step.id)) {
    return cardExpanded.get(step.id) ?? false
  }
  return false
}

function toggleCard(step: ProcessingStep): void {
  cardUserToggled.add(step.id)
  cardExpanded.set(step.id, !isCardExpanded(step))
}
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
