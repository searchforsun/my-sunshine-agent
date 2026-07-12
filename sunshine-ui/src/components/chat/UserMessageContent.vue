<script setup lang="ts">
import { computed } from 'vue'
import MentionChip from './MentionChip.vue'
import type { SkillCatalogIndexEntry } from '../../api/skills'
import type { ExpertCatalogIndexEntry } from '../../api/experts'
import type { WorkflowCatalogEntry } from '../../api/workflows'
import type { ExecutionPreference } from '../../api/executionModes'
import { segmentChatMentionsForMessage } from '../../utils/chatMention'

const props = defineProps<{
  content: string
  catalog: SkillCatalogIndexEntry[]
  expertCatalog?: ExpertCatalogIndexEntry[]
  workflowCatalog?: WorkflowCatalogEntry[]
  executionPreference?: ExecutionPreference
}>()

const segments = computed(() =>
  segmentChatMentionsForMessage(
    props.content,
    {
      skills: props.catalog,
      experts: props.expertCatalog ?? [],
      workflows: props.workflowCatalog ?? [],
    },
    props.executionPreference,
  ),
)

const hasMentionChip = computed(() =>
  segments.value.some(s => s.type !== 'text'),
)
</script>

<template>
  <span v-if="hasMentionChip" class="user-message-content">
    <template v-for="(seg, idx) in segments" :key="idx">
      <MentionChip
        v-if="seg.type === 'skill'"
        kind="skill"
        :token="seg.token"
        :display-name="seg.skill.displayName"
      />
      <MentionChip
        v-else-if="seg.type === 'expert'"
        kind="expert"
        :token="seg.token"
        :display-name="seg.expert.displayName"
      />
      <MentionChip
        v-else-if="seg.type === 'workflow'"
        kind="workflow"
        :token="seg.token"
        :display-name="seg.workflow.displayName"
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
