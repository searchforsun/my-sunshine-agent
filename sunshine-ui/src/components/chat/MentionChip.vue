<script setup lang="ts">
import { computed } from 'vue'
import { NIcon } from 'naive-ui'
import { DocumentTextOutline, FolderOutline } from '@vicons/ionicons5'
import type { ChatMentionKind } from '../../utils/chatMention'
import { mentionPrefix } from '../../utils/chatMention'
import { isLikelySandboxDir } from '../../utils/sandboxPathChip'
import { sandboxPathChipLabel } from '../../utils/skillMentionEditor'
import { useChatStore } from '../../stores/chatStore'
import { useSandboxWorkspaceDrawer } from '../../composables/useSandboxWorkspaceDrawer'

const props = defineProps<{
  kind: ChatMentionKind
  /** skill/expert/workflow：展示 ID；path：完整路径（title / 跳转） */
  token: string
  /** path：basename；其它可选展示名作 title */
  label?: string
  displayName?: string
  /** path 选中行范围：展示 test.py(120-125)，点击定位到起始行 */
  lineStart?: number
  lineEnd?: number
}>()

const chatStore = useChatStore()
const sandboxDrawer = useSandboxWorkspaceDrawer()

const isPath = computed(() => props.kind === 'path')
const pathIsDir = computed(() => isPath.value && isLikelySandboxDir(props.token))
const pathIcon = computed(() => (pathIsDir.value ? FolderOutline : DocumentTextOutline))
const chipLabel = computed(() =>
  isPath.value
    ? sandboxPathChipLabel(props.token, props.label, props.lineStart, props.lineEnd)
    : props.label || props.token,
)

function onPathClick(e: MouseEvent) {
  e.preventDefault()
  e.stopPropagation()
  const cid = chatStore.currentId
  const path = props.token?.trim()
  if (!cid || !path) return
  sandboxDrawer.open({
    conversationId: cid,
    focusPath: path,
    focusLine: props.lineStart,
  })
}
</script>

<template>
  <button
    v-if="isPath"
    type="button"
    class="mention-chip mention-chip--path"
    :title="token"
    @click="onPathClick"
  >
    <NIcon class="mention-chip__icon" :component="pathIcon" :size="13" />
    <span class="mention-chip__label">{{ chipLabel }}</span>
  </button>
  <span
    v-else
    class="mention-chip"
    :class="`mention-chip--${kind}`"
    :title="displayName || token"
  >
    <span v-if="mentionPrefix(kind)" class="mention-chip__prefix">{{ mentionPrefix(kind) }}</span><span class="mention-chip__label">{{ label || token }}</span>
  </span>
</template>
