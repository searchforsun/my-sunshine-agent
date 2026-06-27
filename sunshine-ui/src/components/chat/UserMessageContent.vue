<script setup lang="ts">
import { computed } from 'vue'
import SkillMentionChip from './SkillMentionChip.vue'
import type { SkillCatalogIndexEntry } from '../../api/skills'
import type { ExecutionPreference } from '../../api/executionModes'
import { segmentSkillMentionsForMessage } from '../../utils/skillMention'

const props = defineProps<{
  content: string
  catalog: SkillCatalogIndexEntry[]
  /** 该条 user 消息发送时的 executionPreference */
  executionPreference?: ExecutionPreference
}>()

const segments = computed(() =>
  segmentSkillMentionsForMessage(props.content, props.catalog, props.executionPreference),
)

const hasSkillChip = computed(() =>
  segments.value.some(s => s.type === 'skill'),
)
</script>

<template>
  <span v-if="hasSkillChip" class="user-message-content">
    <template v-for="(seg, idx) in segments" :key="idx">
      <SkillMentionChip
        v-if="seg.type === 'skill'"
        :token="seg.token"
        :display-name="seg.skill.displayName"
      />
      <span v-else>{{ seg.value }}</span>
    </template>
  </span>
  <span v-else>{{ content }}</span>
</template>

<style scoped>
.user-message-content {
  display: inline;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: inherit;
}
</style>
