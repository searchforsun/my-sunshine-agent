<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { Conversation } from '../stores/chatStore'
import { formatConversationTime } from '../utils/conversationTime'
import { listCheckouts } from '../api/workspaceGit'

/**
 * 会话 hover 详情卡（IDE 风格）：标题完整显示 + 信息行（项目名 / 更新时间 / 真实分支名）。
 * 分支名由 checkoutId 反查得到（checkoutPath 只含目录 ID，不含分支名）。
 * 延时出现避免划过时闪烁；位置相对触发项右侧浮出。
 */
const props = defineProps<{
  conversation: Conversation
  /** 触发元素，用于定位 */
  anchor: HTMLElement | null
  /** 工作区项目名（仅任务会话） */
  workspaceName?: string
}>()

const visible = ref(false)
const top = ref(0)
const left = ref(0)
let showTimer: ReturnType<typeof setTimeout> | null = null

/** 反查 checkoutId 对应的真实分支名；未就绪返回空串 */
const branchName = ref('')
async function resolveBranchName() {
  branchName.value = ''
  const conv = props.conversation
  if (conv.kind !== 'task' || !conv.workspaceId || !conv.checkoutPath) return
  const checkoutId = conv.checkoutPath.split('/').pop() || conv.checkoutPath
  try {
    const cs = await listCheckouts(conv.workspaceId)
    branchName.value = cs.find(c => c.checkoutId === checkoutId)?.branch || checkoutId
  } catch {
    branchName.value = checkoutId
  }
}

watch(() => props.conversation, () => {
  void resolveBranchName()
}, { immediate: true })

const infoRows = computed(() => {
  const rows: { icon: 'folder' | 'clock' | 'branch'; text: string }[] = []
  if (props.conversation.kind === 'task' && props.workspaceName) {
    rows.push({ icon: 'folder', text: props.workspaceName })
  }
  const ended = props.conversation.updatedAt
  if (ended) {
    const msgCount = props.conversation.messages?.length ?? 0
    rows.push({
      icon: 'clock',
      text: msgCount > 0 ? `更新于 ${formatConversationTime(ended)} · ${msgCount} 条消息` : `更新于 ${formatConversationTime(ended)}`,
    })
  }
  if (props.conversation.kind === 'task' && props.conversation.checkoutPath) {
    rows.push({ icon: 'branch', text: branchName.value || '未知分支' })
  }
  return rows
})

function show() {
  if (showTimer) clearTimeout(showTimer)
  showTimer = setTimeout(() => {
    if (!props.anchor) return
    const rect = props.anchor.getBoundingClientRect()
    top.value = rect.top
    left.value = rect.right + 8
    visible.value = true
  }, 350)
}

function hide() {
  if (showTimer) clearTimeout(showTimer)
  showTimer = null
  visible.value = false
}

onBeforeUnmount(() => {
  if (showTimer) clearTimeout(showTimer)
})

defineExpose({ show, hide })
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      class="conv-hover-card"
      :style="{ top: `${top}px`, left: `${left}px` }"
      role="tooltip"
    >
      <div class="conv-hover-title">{{ conversation.title }}</div>
      <div v-for="(row, i) in infoRows" :key="i" class="conv-hover-row">
        <svg v-if="row.icon === 'clock'" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <circle cx="12" cy="12" r="10" /><polyline points="12 6 12 12 16 14" />
        </svg>
        <svg v-else-if="row.icon === 'folder'" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z" />
        </svg>
        <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <line x1="6" y1="3" x2="6" y2="15" /><circle cx="18" cy="6" r="3" /><circle cx="6" cy="18" r="3" /><path d="M18 9a9 9 0 0 1-9 9" />
        </svg>
        <span class="conv-hover-row-text">{{ row.text }}</span>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.conv-hover-card {
  position: fixed;
  z-index: 1000;
  min-width: 220px;
  max-width: 340px;
  padding: 10px 12px;
  background: var(--sun-black);
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-elevated);
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.conv-hover-title {
  font-size: var(--sun-font-base);
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.4;
  word-break: break-word;
  white-space: normal;
}

.conv-hover-row {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.3;
}

.conv-hover-row svg {
  flex-shrink: 0;
  opacity: 0.8;
}

.conv-hover-row-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
