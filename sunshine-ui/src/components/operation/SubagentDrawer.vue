<script setup lang="ts">
import { computed, inject, type ComputedRef } from 'vue'
import type { ProcessingStep } from '../../api/processingSteps'
import {
  formatDuration,
  formatStepLabel,
  resolveStepDurationMs,
  stepLifecycle,
} from '../../api/processingSteps'
import type { HitlConfirmationPayload } from '../../api/hitlSteps'
import { stepHasHitlAwaiting } from '../../api/recoverySteps'
import { useSubagentDrawer } from '../../composables/useSubagentDrawer'
import DrawerCollapseIcon from '../icons/DrawerCollapseIcon.vue'
import StaticMarkdown from '../StaticMarkdown.vue'
import OperationStack from './OperationStack.vue'

const { state, close, drawerWidth } = useSubagentDrawer()
const applyHitlDecision = inject<(token: string, approved: boolean) => void>('applyHitlDecision', () => {})
const resolveLiveStep = inject<(stepId: string) => ProcessingStep | undefined>(
  'subagentDrawerLiveStep',
  () => undefined,
)
const pendingHitlList = inject<ComputedRef<HitlConfirmationPayload[]>>(
  'pendingHitlConfirmations',
  computed(() => []),
)

const step = computed(() => {
  const id = state.step?.id
  if (id) {
    const live = resolveLiveStep(id)
    if (live) return live
  }
  return state.step
})

const title = computed(() => (step.value ? formatStepLabel(step.value) : '') || '子任务')
const lifecycle = computed(() => (step.value ? stepLifecycle(step.value) : 'pending'))
const awaitingConfirm = computed(() => stepHasHitlAwaiting(step.value))

const displayStatus = computed(() => {
  if (awaitingConfirm.value) return 'awaiting_confirm'
  if (lifecycle.value === 'error') return 'error'
  if (lifecycle.value === 'done') return 'done'
  if (lifecycle.value === 'running') return 'running'
  return 'pending'
})

const statusLabel = computed(() => {
  switch (displayStatus.value) {
    case 'awaiting_confirm': return '待确认'
    case 'error': return '失败'
    case 'done': return '已完成'
    case 'running': return '执行中'
    default: return '等待中'
  }
})

const durationText = computed(() => {
  if (!step.value) return ''
  if (displayStatus.value !== 'done' && displayStatus.value !== 'error') return ''
  const ms = resolveStepDurationMs(step.value)
  return ms != null ? formatDuration(ms) : ''
})

const spawnPrompt = computed(() => step.value?.metadata?.spawnPrompt?.trim() ?? '')
const subSteps = computed(() => step.value?.subSteps ?? [])
const finalOutput = computed(() => step.value?.result?.trim() ?? '')
const subTimelineLive = computed(() =>
  displayStatus.value === 'running' || displayStatus.value === 'awaiting_confirm',
)
</script>

<template>
  <aside
    v-if="state.open && step"
    class="subagent-drawer"
    role="complementary"
    aria-label="子任务详情"
    :style="{ width: `${drawerWidth}px` }"
  >
    <header class="drawer-header">
      <div class="drawer-head-top">
        <div class="drawer-title-row">
          <span class="drawer-type-icon" aria-hidden="true">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <rect x="2.5" y="2.5" width="5" height="5" rx="1" stroke="currentColor" stroke-width="1.2" />
              <rect x="8.5" y="8.5" width="5" height="5" rx="1" stroke="currentColor" stroke-width="1.2" />
              <path d="M7.5 5h2.5v2.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>
          <h3 class="drawer-title">{{ title }}</h3>
        </div>
        <button type="button" class="drawer-close" title="收起" aria-label="收起" @click="close">
          <DrawerCollapseIcon :size="16" />
        </button>
      </div>

      <div class="drawer-status-row">
        <span class="meta-status" :class="`is-${displayStatus}`">
          <span class="status-dot" aria-hidden="true" />
          {{ statusLabel }}
        </span>
        <span v-if="durationText" class="meta-dur">{{ durationText }}</span>
      </div>
    </header>

    <div class="drawer-body">
      <section v-if="spawnPrompt" class="drawer-section">
        <h4>传入提示词</h4>
        <pre class="spawn-prompt">{{ spawnPrompt }}</pre>
      </section>

      <section v-if="subSteps.length" class="drawer-section drawer-sub-timeline">
        <h4>执行过程</h4>
        <OperationStack
          :steps="subSteps"
          :stream-live="subTimelineLive"
          :live="subTimelineLive"
          :embed-hitl="false"
          :pending-hitl-confirmations="pendingHitlList"
          @hitl-decided="applyHitlDecision"
        />
      </section>

      <section v-if="finalOutput" class="drawer-section">
        <h4>最终输出</h4>
        <StaticMarkdown :source="finalOutput" />
      </section>
    </div>
  </aside>
</template>

<style scoped>
.subagent-drawer {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  border-left: 1px solid var(--sun-border);
  background: var(--sun-black);
  z-index: 200;
}

.drawer-header {
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.drawer-head-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.drawer-title-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.drawer-type-icon {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-secondary);
}

.drawer-title {
  margin: 0;
  padding-top: 2px;
  font-size: var(--sun-font-lg);
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.35;
  word-break: break-word;
}

.drawer-close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.drawer-close:hover {
  color: var(--sun-text);
}

.drawer-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.meta-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 500;
  color: var(--sun-text-muted);
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}

.meta-status.is-pending { color: var(--sun-text-muted); }
.meta-status.is-running { color: var(--sun-blue, #58a6ff); }
.meta-status.is-awaiting_confirm { color: var(--sun-purple, #9333ea); }
.meta-status.is-done { color: var(--sun-green, #3fb950); }
.meta-status.is-error { color: var(--sun-red, #f85149); }

.meta-dur {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--sun-text-secondary);
  font-variant-numeric: tabular-nums;
}

.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 16px 16px 28px;
}

.drawer-section {
  margin-bottom: 16px;
}

.drawer-section h4 {
  margin: 0 0 8px;
  font-size: var(--sun-font-sm);
  font-weight: 600;
  color: var(--sun-text-secondary);
}

.spawn-prompt {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  background: var(--sun-black);
  color: var(--sun-text);
  font-family: var(--sun-font-mono, ui-monospace, monospace);
  font-size: var(--sun-font-sm);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.drawer-sub-timeline :deep(.operation-lines) {
  padding-bottom: 0;
}
</style>
