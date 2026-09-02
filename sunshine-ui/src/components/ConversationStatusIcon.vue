<script setup lang="ts">
import type { SidebarConvIndicator } from '../composables/useConversationSidebarIndicator'

defineProps<{
  state: SidebarConvIndicator | null
  active?: boolean
  title?: string
}>()
</script>

<template>
  <span
    class="conv-icon-wrap"
    :class="{
      'conv-icon-wrap--hitl': state === 'hitl_pending' || state === 'decision_pending',
    }"
    :title="title"
  >
  <!-- 空闲：圆角气泡轮廓（Cursor 非活动 Tab） -->
  <svg
    v-if="!state"
    class="conv-icon conv-icon--idle"
    :class="{ 'conv-icon--active': active }"
    width="16"
    height="16"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <path
      d="M3.5 2.75h9a1.25 1.25 0 0 1 1.25 1.25v5.5a1.25 1.25 0 0 1-1.25 1.25H7.2L4.6 12.1a.55.55 0 0 1-.85-.46V4a1.25 1.25 0 0 1 1.25-1.25Z"
      stroke="currentColor"
      stroke-width="1.25"
      stroke-linejoin="round"
    />
  </svg>

  <!-- 生成中：星芒旋转（Cursor 活动 Tab） -->
  <svg
    v-else-if="state === 'streaming'"
    class="conv-icon conv-icon--streaming"
    width="16"
    height="16"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <g stroke="currentColor" stroke-width="1.35" stroke-linecap="round">
      <line x1="8" y1="1.2" x2="8" y2="3.6" />
      <line x1="8" y1="12.4" x2="8" y2="14.8" />
      <line x1="1.2" y1="8" x2="3.6" y2="8" />
      <line x1="12.4" y1="8" x2="14.8" y2="8" />
      <line x1="3.15" y1="3.15" x2="4.85" y2="4.85" />
      <line x1="11.15" y1="11.15" x2="12.85" y2="12.85" />
      <line x1="11.15" y1="4.85" x2="12.85" y2="3.15" />
      <line x1="3.15" y1="12.85" x2="4.85" y2="11.15" />
    </g>
  </svg>

  <!-- 待决策：琥珀圆 + 问号（request_decision） -->
  <svg
    v-else-if="state === 'decision_pending'"
    class="conv-icon conv-icon--hitl conv-icon--decision"
    width="16"
    height="16"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <circle class="hitl-badge" cx="8" cy="8" r="7.25" />
    <path
      class="hitl-mark"
      d="M5.85 6.15a2.15 2.15 0 1 1 2.55 2.05c-.55.2-.9.7-.9 1.3"
      stroke-linecap="round"
      fill="none"
    />
    <circle class="hitl-mark hitl-dot" cx="8" cy="11.45" r="0.95" />
  </svg>

  <!-- 待确认：琥珀实心圆 + 感叹号（需人工确认） -->
  <svg
    v-else-if="state === 'hitl_pending'"
    class="conv-icon conv-icon--hitl"
    width="16"
    height="16"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <circle class="hitl-badge" cx="8" cy="8" r="7.25" />
    <line class="hitl-mark" x1="8" y1="4.4" x2="8" y2="9.1" stroke-linecap="round" />
    <circle class="hitl-mark hitl-dot" cx="8" cy="11.35" r="0.95" />
  </svg>

  <!-- 新回复：气泡 + 右上角实心点 -->
  <svg
    v-else
    class="conv-icon conv-icon--completed"
    width="16"
    height="16"
    viewBox="0 0 16 16"
    fill="none"
    aria-hidden="true"
  >
    <path
      d="M3.5 2.75h7.2a1.25 1.25 0 0 1 1.25 1.25v5a1.25 1.25 0 0 1-1.25 1.25H7.2L4.6 11.6a.55.55 0 0 1-.85-.46V4a1.25 1.25 0 0 1 1.25-1.25Z"
      stroke="currentColor"
      stroke-width="1.25"
      stroke-linejoin="round"
    />
    <circle cx="12.1" cy="3.9" r="2.1" fill="#ef4444" />
  </svg>
  </span>
</template>

<style scoped>
.conv-icon-wrap {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.conv-icon {
  display: block;
  flex-shrink: 0;
}

.conv-icon--idle {
  color: var(--sun-text-muted);
  opacity: 0.72;
}

.conv-icon--idle.conv-icon--active {
  color: var(--sun-text-secondary);
  opacity: 1;
}

.conv-icon--streaming {
  color: var(--sun-text);
}

.conv-icon-wrap--hitl {
  width: 18px;
  height: 18px;
}

.conv-icon--hitl .hitl-badge {
  fill: var(--sun-amber-glow);
  stroke: var(--sun-amber);
  stroke-width: 1.15;
}

.conv-icon--hitl .hitl-mark {
  stroke: var(--sun-amber-light);
  stroke-width: 1.5;
  fill: var(--sun-amber-light);
}

.conv-icon--decision .hitl-mark {
  stroke-width: 1.35;
  fill: none;
}

.conv-icon--hitl .hitl-dot {
  stroke: none;
  fill: var(--sun-amber-light);
}

.conv-icon--completed {
  color: var(--sun-text-secondary);
}
</style>
