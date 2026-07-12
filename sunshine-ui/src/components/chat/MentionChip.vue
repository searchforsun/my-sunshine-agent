<script setup lang="ts">
import type { ChatMentionKind } from '../../utils/chatMention'
import { mentionPrefix } from '../../utils/chatMention'

defineProps<{
  kind: ChatMentionKind
  token: string
  displayName?: string
}>()
</script>

<template>
  <span
    class="mention-chip"
    :class="`mention-chip--${kind}`"
    :title="displayName || token"
  >
    <span class="mention-chip__prefix">{{ mentionPrefix(kind) }}</span><span class="mention-chip__label">{{ token }}</span>
  </span>
</template>
