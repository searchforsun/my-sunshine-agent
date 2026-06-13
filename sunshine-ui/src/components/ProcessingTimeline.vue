<script setup lang="ts">

import { computed } from 'vue'

import type { ProcessingStep } from '../api/processingSteps'

import {

  formatDuration,

  summarizeSteps,

  totalDuration,

} from '../api/processingSteps'



const props = defineProps<{

  steps: ProcessingStep[]

  expanded: boolean

  live?: boolean

}>()



const emit = defineEmits<{

  toggle: []

}>()



const summary = computed(() => summarizeSteps(props.steps))

const totalMs = computed(() => totalDuration(props.steps))

const showTotal = computed(() => props.steps.some(s => s.durationMs != null && (s.lifecycle ?? s.status) === 'done'))



const lifecycleOf = (step: ProcessingStep) => step.lifecycle ?? step.status ?? 'pending'



const statusIcon = (step: ProcessingStep) => {

  switch (lifecycleOf(step)) {

    case 'done': return 'done'

    case 'running': return 'running'

    case 'error': return 'error'

    case 'skipped': return 'skipped'

    default: return 'pending'

  }

}



const stepLabel = (step: ProcessingStep) => step.label ?? step.id



const hasSummary = (step: ProcessingStep) =>

  !!(step.summary?.before || step.summary?.active || step.summary?.after)



const isRunning = (step: ProcessingStep) => lifecycleOf(step) === 'running'

const isDone = (step: ProcessingStep) => lifecycleOf(step) === 'done'

</script>



<template>

  <div class="timeline-panel" :class="{ 'is-expanded': expanded, 'is-live': live }">

    <button type="button" class="timeline-toggle" @click="emit('toggle')">

      <svg

        class="timeline-chevron"

        width="14"

        height="14"

        viewBox="0 0 24 24"

        fill="none"

        stroke="currentColor"

        stroke-width="2"

        stroke-linecap="round"

      >

        <polyline points="9 18 15 12 9 6" />

      </svg>

      <span class="timeline-title">处理过程</span>

      <span v-if="live" class="timeline-badge">处理中</span>

      <span v-if="!expanded && summary" class="timeline-preview">{{ summary }}</span>

      <span v-if="showTotal && totalMs" class="timeline-total">总耗时 {{ formatDuration(totalMs) }}</span>

    </button>

    <div v-show="expanded" class="timeline-body">

      <div

        v-for="(step, index) in steps"

        :key="step.id"

        class="timeline-row"

        :class="[`is-${statusIcon(step)}`, { 'is-last': index === steps.length - 1 }]"

      >

        <div class="timeline-axis">

          <span class="timeline-dot" aria-hidden="true" />

          <span v-if="index < steps.length - 1" class="timeline-spine" aria-hidden="true" />

        </div>

        <div class="timeline-content">

          <div class="timeline-header">

            <span class="timeline-label">{{ stepLabel(step) }}</span>

            <span v-if="isDone(step) && step.durationMs != null" class="timeline-duration">

              {{ formatDuration(step.durationMs) }}

            </span>

            <span v-else-if="isRunning(step)" class="timeline-duration is-pulse">…</span>

          </div>

          <div v-if="hasSummary(step)" class="timeline-summary">

            <div v-if="step.summary?.before" class="summary-line">{{ step.summary.before }}</div>

            <div

              v-if="step.summary?.active"

              class="summary-line"

              :class="{ 'is-active': isRunning(step) }"

            >

              {{ step.summary.active }}<span v-if="isRunning(step)" class="active-pulse">…</span>

            </div>

            <div v-if="step.summary?.after" class="summary-line is-after">

              <span v-if="isDone(step)" class="summary-check" aria-hidden="true">✓</span>

              {{ step.summary.after }}

            </div>

          </div>

          <div v-else-if="step.detail" class="timeline-fallback">

            <span class="timeline-detail">{{ step.detail }}</span>

          </div>

        </div>

      </div>

    </div>

  </div>

</template>



<style scoped>

.timeline-panel {

  position: relative;

  z-index: 3;

  margin-bottom: 10px;

  border: 1px solid var(--sun-border, #263348);

  border-left: 3px solid var(--sun-blue, #3b82f6);

  border-radius: 10px;

  background: rgba(59, 130, 246, 0.06);

  overflow: hidden;

}



.timeline-panel.is-live {

  border-color: rgba(59, 130, 246, 0.35);

  box-shadow: 0 0 0 1px rgba(59, 130, 246, 0.08);

}



.timeline-toggle {

  display: flex;

  align-items: center;

  gap: 8px;

  width: 100%;

  padding: 8px 12px;

  border: none;

  background: transparent;

  color: var(--sun-text-secondary, #94a3b8);

  font-size: 13px;

  cursor: pointer;

  text-align: left;

  font-family: inherit;

}



.timeline-toggle:hover {

  background: rgba(255, 255, 255, 0.04);

  color: var(--sun-text, #e2e8f0);

}



.timeline-chevron {

  flex-shrink: 0;

  transition: transform 0.2s ease;

  color: var(--sun-blue-light, #60a5fa);

}



.is-expanded .timeline-chevron {

  transform: rotate(90deg);

}



.timeline-title {

  font-weight: 600;

  color: var(--sun-blue-light, #60a5fa);

  flex-shrink: 0;

}



.timeline-badge {

  flex-shrink: 0;

  padding: 1px 8px;

  border-radius: 999px;

  font-size: 11px;

  background: rgba(59, 130, 246, 0.15);

  color: var(--sun-blue-light, #60a5fa);

}



.timeline-preview {

  flex: 1;

  min-width: 0;

  overflow: hidden;

  text-overflow: ellipsis;

  white-space: nowrap;

  font-size: 12px;

  color: var(--sun-text-muted, #64748b);

}



.timeline-total {

  flex-shrink: 0;

  margin-left: auto;

  font-size: 11px;

  color: var(--sun-text-muted, #64748b);

  font-variant-numeric: tabular-nums;

}



.timeline-body {

  border-top: 1px solid rgba(59, 130, 246, 0.12);

  padding: 8px 12px 10px;

  display: flex;

  flex-direction: column;

  gap: 0;

}



.timeline-row {

  display: flex;

  gap: 10px;

  font-size: 12px;

  color: var(--sun-text-muted, #64748b);

  padding-bottom: 10px;

}



.timeline-row.is-last {

  padding-bottom: 0;

}



.timeline-row.is-done {

  color: var(--sun-text-secondary, #94a3b8);

}



.timeline-row.is-running {

  color: var(--sun-blue-light, #60a5fa);

}



.timeline-row.is-error {

  color: #f87171;

}



.timeline-axis {

  display: flex;

  flex-direction: column;

  align-items: center;

  flex-shrink: 0;

  width: 12px;

}



.timeline-dot {

  width: 8px;

  height: 8px;

  border-radius: 50%;

  flex-shrink: 0;

  background: var(--sun-border, #334155);

  margin-top: 4px;

}



.timeline-row.is-done .timeline-dot {

  background: #22c55e;

}



.timeline-row.is-running .timeline-dot {

  background: var(--sun-blue, #3b82f6);

  animation: pulse 1.2s ease-in-out infinite;

}



.timeline-row.is-error .timeline-dot {

  background: #ef4444;

}



.timeline-spine {

  flex: 1;

  width: 2px;

  min-height: 12px;

  margin-top: 4px;

  background: var(--sun-border, #334155);

  border-radius: 1px;

}



.timeline-content {

  flex: 1;

  min-width: 0;

}



.timeline-header {

  display: flex;

  align-items: baseline;

  gap: 8px;

}



.timeline-label {

  flex: 1;

  font-weight: 500;

  color: inherit;

}



.timeline-duration {

  flex-shrink: 0;

  font-size: 11px;

  color: var(--sun-text-muted, #64748b);

  font-variant-numeric: tabular-nums;

}



.timeline-duration.is-pulse {

  color: var(--sun-blue-light, #60a5fa);

  animation: pulse 1.2s ease-in-out infinite;

}



.timeline-summary {

  margin-top: 4px;

  display: flex;

  flex-direction: column;

  gap: 2px;

}



.summary-line {

  font-size: 11px;

  color: var(--sun-text-muted, #64748b);

  line-height: 1.45;

}



.summary-line.is-active {

  color: var(--sun-blue-light, #60a5fa);

}



.summary-line.is-after {

  color: var(--sun-text-secondary, #94a3b8);

}



.summary-check {

  margin-right: 4px;

  color: #22c55e;

}



.active-pulse {

  margin-left: 2px;

}



.timeline-fallback {

  margin-top: 2px;

}



.timeline-detail {

  font-size: 11px;

  color: var(--sun-text-muted, #64748b);

}



@keyframes pulse {

  0%, 100% { opacity: 1; transform: scale(1); }

  50% { opacity: 0.55; transform: scale(0.85); }

}

</style>

