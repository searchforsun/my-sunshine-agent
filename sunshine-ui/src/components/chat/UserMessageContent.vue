<script setup lang="ts">
import { computed } from 'vue'
import MentionChip from './MentionChip.vue'
import type { SkillCatalogIndexEntry } from '../../api/skills'
import type { AgentCatalogIndexEntry } from '../../api/agents'
import type { WorkflowCatalogEntry } from '../../api/workflows'
import type { ExecutionPreference } from '../../api/executionModes'
import { segmentChatMentionsForMessage } from '../../utils/chatMention'

const props = defineProps<{
  content: string
  catalog: SkillCatalogIndexEntry[]
  agentCatalog?: AgentCatalogIndexEntry[]
  workflowCatalog?: WorkflowCatalogEntry[]
  executionPreference?: ExecutionPreference
}>()

const segments = computed(() =>
  segmentChatMentionsForMessage(
    props.content,
    {
      skills: props.catalog,
      agents: props.agentCatalog ?? [],
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
        v-else-if="seg.type === 'agent'"
        kind="agent"
        :token="seg.token"
        :display-name="seg.agent.displayName"
      />
      <MentionChip
        v-else-if="seg.type === 'workflow'"
        kind="workflow"
        :token="seg.token"
        :display-name="seg.workflow.displayName"
      />
      <MentionChip
        v-else-if="seg.type === 'path'"
        kind="path"
        :token="seg.token"
        :label="seg.label"
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
